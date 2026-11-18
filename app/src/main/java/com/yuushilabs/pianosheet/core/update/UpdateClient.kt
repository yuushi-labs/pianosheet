package com.yuushilabs.pianosheet.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Reads the latest-release document; kept behind an interface so the update flow can be tested. */
interface ReleaseFetcher {
    suspend fun fetchLatest(): Result<String>
}

/**
 * `GET /repos/{owner}/{repo}/releases/latest` with the headers GitHub asks for.
 *
 * The endpoint returns the newest *promoted* release, so drafts and
 * pre-releases never reach the app; a 404 (no release yet) or any network
 * trouble is a failure the caller treats quietly.
 */
class HttpReleaseFetcher(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : ReleaseFetcher {

    override suspend fun fetchLatest(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val error = connection.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?.take(200)
                        .orEmpty()
                    throw IOException("HTTP $code $error".trim())
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(8 * 1024)
                    val out = StringBuilder()
                    while (out.length < MAX_RESPONSE_CHARS) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        out.appendRange(buffer, 0, read)
                    }
                    out.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.github.com/repos/yuushi-labs/pianosheet/releases/latest"
        const val DEFAULT_USER_AGENT = "PianoSheet-Android"

        /** The release document is a few KB; anything larger is not one. */
        private const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}

/** Fetches an APK to a local file, reporting bytes as they arrive. */
interface ApkDownloader {
    /**
     * Downloads [url] into [target].  [onProgress] receives
     * (bytes so far, total bytes — 0 when the server does not say).
     * Throws [IOException] when the transfer fails or the bytes are not an APK.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): File
}

/** The checks a finished download must pass before it is offered to the installer. */
object ApkFiles {

    /** ZIP magic (`PK\u0003\u0004`): an APK is a zip, an error page is not. */
    fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4) return@use false
            header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    fun sizeMatches(file: File, expectedBytes: Long): Boolean =
        file.isFile && file.length() > 0 && (expectedBytes <= 0 || file.length() == expectedBytes)
}

class HttpApkDownloader(
    private val userAgent: String = HttpReleaseFetcher.DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : ApkDownloader {

    override suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("APK download failed: HTTP $code")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
            var received = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                }
            }
            if (!ApkFiles.sizeMatches(partial, expectedBytes)) {
                throw IOException("APK download did not match: $received bytes")
            }
            if (!ApkFiles.looksLikeApk(partial)) {
                throw IOException("downloaded file is not an APK")
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
