package com.yuushilabs.pianosheet.data.update

import com.yuushilabs.pianosheet.core.update.ApkDownloader
import com.yuushilabs.pianosheet.core.update.ApkInstaller
import com.yuushilabs.pianosheet.core.update.ReleaseFetcher
import java.io.File

/**
 * The three test doubles the update flow needs: the GitHub answer, the transfer
 * and what is remembered between launches.  They stand in for the network so
 * 0.4.5's policy runs in a plain JVM test.
 */
class FakeReleaseFetcher(
    private val body: String? = null,
    private val error: Throwable? = null,
) : ReleaseFetcher {
    var calls = 0

    override suspend fun fetchLatest(): Result<String> {
        calls++
        error?.let { return Result.failure(it) }
        return Result.success(body.orEmpty())
    }
}

class FakeApkDownloader(
    private val apkBytes: Long = 3_000_000L,
    private val failure: Throwable? = null,
) : ApkDownloader {
    var calls = 0

    override suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): File {
        calls++
        failure?.let { throw it }
        target.parentFile?.mkdirs()
        // A minimal zip header: what ApkFiles looks for.
        target.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        java.io.RandomAccessFile(target, "rw").use { it.setLength(apkBytes) }
        onProgress(apkBytes, apkBytes)
        return target
    }
}

class InMemoryUpdateMemo(
    var checkedAt: Long? = null,
    var skipped: String? = null,
) : UpdateMemo {
    override suspend fun lastCheckedAt(): Long? = checkedAt

    override suspend fun skippedVersion(): String? = skipped

    override suspend fun recordCheck(at: Long) {
        checkedAt = at
    }

    override suspend fun skip(version: String) {
        skipped = version
    }

    override suspend fun clearSkip() {
        skipped = null
    }
}

/** Never touches Android: the controller test only asks whether a download may be installed. */
object NoOpInstaller : ApkInstaller {
    override fun canInstallPackages(): Boolean = true

    override fun unknownSourcesSettingsIntent() = throw UnsupportedOperationException("no UI here")

    override fun installIntent(apk: File) = throw UnsupportedOperationException("no UI here")
}

/** The `/releases/latest` body the CI publishes for [tag]. */
fun releasePayload(tag: String, size: Long = 3_000_000L): String = """
    {
      "tag_name": "$tag",
      "name": "PianoSheet $tag",
      "draft": false,
      "prerelease": false,
      "body": "- アプリ内アップデート",
      "html_url": "https://github.com/yuushi-labs/pianosheet/releases/tag/$tag",
      "assets": [
        {
          "name": "pianosheet-$tag-debug.apk",
          "size": $size,
          "content_type": "application/vnd.android.package-archive",
          "browser_download_url": "https://github.com/yuushi-labs/pianosheet/releases/download/$tag/pianosheet-$tag-debug.apk"
        }
      ]
    }
""".trimIndent()
