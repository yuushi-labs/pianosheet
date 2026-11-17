package com.yuushilabs.pianosheet.core.storage

import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which public MediaStore collection a shared file is written to. */
enum class SharedCollection {
    /** `MediaStore.Audio` — shows up in music players, lives in `Music/`. */
    MUSIC,

    /** `MediaStore.Files` — a document row, lives in `Documents/` or `Music/`. */
    DOCUMENT,
}

/** One insert into a public folder: where, under what name, and as what type. */
data class SharedWriteRequest(
    val collection: SharedCollection,
    /** Volume-relative folder, e.g. `Music/PianoSheet`. */
    val relativeFolder: String,
    val fileName: String,
    val mimeType: String,
)

/** A file that now belongs to the public folder, not to this app's storage. */
data class SharedFile(
    val uri: String,
    /** Full path for the screen, e.g. `/storage/emulated/0/Music/PianoSheet/rec-....wav`. */
    val displayPath: String,
    val fileName: String,
    val bytes: Long,
    val collection: SharedCollection,
)

/**
 * The MediaStore calls this class needs.  Keeping them behind an interface means
 * the naming, fallback and import policy below is plain Kotlin and can be tested
 * without a device (the Android implementation is
 * [com.yuushilabs.pianosheet.core.storage.AndroidSharedMediaGateway]).
 */
interface SharedMediaGateway {
    /** Inserts the row, copies [openSource] into it and returns the finished file. Throws on failure. */
    fun write(request: SharedWriteRequest, openSource: () -> InputStream): SharedFile

    fun openInput(uri: String): InputStream?

    fun displayName(uri: String): String?
}

/**
 * Recordings and score exports that survive an uninstall.
 *
 * Android gives an app no private folder that outlives the app, so the raw take
 * is written twice: the working copy in `filesDir` (what the analysis reads, and
 * what keeps a take safe if the shared write fails) and an untouched copy in a
 * public folder through MediaStore.  The public copy needs no storage
 * permission on API 29+, and uninstalling PianoSheet does not delete it.
 *
 * This class owns the policy only: names, folders, the fallback when one
 * collection refuses the write, and reading a saved take back in.
 */
class SharedAudioStorage(private val gateway: SharedMediaGateway) {

    /**
     * Copies the raw [source] take into `Music/PianoSheet/`.
     *
     * The app-private file is never touched, so a failure here costs the shared
     * copy only: the take can still be analysed, saved (SAF) or shared.
     */
    fun saveRecording(
        source: File,
        projectId: String,
        capturedAt: Long = System.currentTimeMillis(),
    ): Result<SharedFile> {
        if (!source.isFile || source.length() <= 0L) {
            return Result.failure(IllegalStateException("録音ファイルが見つかりません"))
        }
        val name = audioFileName(projectId, capturedAt)
        return writeWithFallback(
            primary = SharedWriteRequest(SharedCollection.MUSIC, MUSIC_FOLDER, name, WAV_MIME_TYPE),
            // Some devices refuse a .wav row in the audio collection (a vendor
            // media scanner without an audio entry).  The same folder written as
            // a plain file row still lands in Music/PianoSheet.
            fallback = SharedWriteRequest(SharedCollection.DOCUMENT, MUSIC_FOLDER, name, WAV_MIME_TYPE),
        ) { source.inputStream() }
    }

    /** Writes an exported score (MusicXML, MIDI) into `Documents/PianoSheet/`. */
    fun saveDocument(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        savedAt: Long = System.currentTimeMillis(),
    ): Result<SharedFile> {
        if (bytes.isEmpty()) return Result.failure(IllegalStateException("書き出す内容がありません"))
        val name = documentFileName(fileName, savedAt)
        return writeWithFallback(
            primary = SharedWriteRequest(SharedCollection.DOCUMENT, DOCUMENTS_FOLDER, name, mimeType),
            // A vendor file provider without the MusicXML MIME type still takes
            // the bytes when they are declared as a generic file.
            fallback = SharedWriteRequest(SharedCollection.DOCUMENT, DOCUMENTS_FOLDER, name, OCTET_STREAM),
        ) { bytes.inputStream() }
    }

    /**
     * Reads a take that was saved to a public folder back into the app (after a
     * reinstall the working copy is gone; the shared WAV is not).
     *
     * Only WAV is accepted: the final analysis reads RIFF/WAVE directly, so the
     * header is checked before anything is copied.
     */
    fun importRecording(
        uri: String,
        projectId: String,
        recordingsDir: File,
    ): Result<File> {
        val shownName = gateway.displayName(uri)
        val input = gateway.openInput(uri)
            ?: return Result.failure(IllegalStateException("ファイルを開けませんでした"))
        val target = File(recordingsDir, "import-${shortId(projectId)}.wav")
        return try {
            input.use { stream ->
                val header = ByteArray(WAV_HEADER_BYTES)
                var read = 0
                while (read < header.size) {
                    val count = stream.read(header, read, header.size - read)
                    if (count <= 0) break
                    read += count
                }
                if (read < WAV_HEADER_BYTES || !looksLikeWav(header)) {
                    return Result.failure(
                        IllegalArgumentException("WAV ファイルではありません（${shownName ?: uri}）"),
                    )
                }
                recordingsDir.mkdirs()
                target.outputStream().use { output ->
                    output.write(header, 0, read)
                    stream.copyTo(output, 1 shl 16)
                }
            }
            if (target.length() <= WAV_HEADER_BYTES) {
                target.delete()
                Result.failure(IllegalStateException("ファイルが空でした（${shownName ?: uri}）"))
            } else {
                Result.success(target)
            }
        } catch (error: Exception) {
            target.delete()
            Result.failure(
                IllegalStateException(
                    "取り込めませんでした: ${error.message ?: error.javaClass.simpleName}",
                    error,
                ),
            )
        }
    }

    /** Tries the natural collection first, then the fallback; no write is lost silently. */
    private fun writeWithFallback(
        primary: SharedWriteRequest,
        fallback: SharedWriteRequest,
        openSource: () -> InputStream,
    ): Result<SharedFile> {
        val firstError: Exception = try {
            return Result.success(gateway.write(primary, openSource))
        } catch (error: Exception) {
            error
        }
        try {
            return Result.success(gateway.write(fallback, openSource))
        } catch (error: Exception) {
            val reason = error.message ?: firstError.message ?: error.javaClass.simpleName
            return Result.failure(
                IllegalStateException("共有フォルダに保存できませんでした: $reason", error),
            )
        }
    }

    companion object {
        /** Folder under `Music/` where every take is kept. */
        const val MUSIC_FOLDER = "Music/PianoSheet"

        /** Folder under `Documents/` where exported scores are kept. */
        const val DOCUMENTS_FOLDER = "Documents/PianoSheet"

        const val WAV_MIME_TYPE = "audio/wav"
        const val OCTET_STREAM = "application/octet-stream"

        private const val WAV_HEADER_BYTES = 12
        private const val MAX_BASE_LENGTH = 60

        /** `/storage/emulated/0/Music/PianoSheet` — what the screens show. */
        fun folderPath(relativeFolder: String = MUSIC_FOLDER): String =
            "/storage/emulated/0/" + relativeFolder.trim('/')

        /** `/storage/emulated/0/Music/PianoSheet/rec-....wav`. */
        fun displayPath(relativeFolder: String, fileName: String): String =
            folderPath(relativeFolder) + "/" + fileName

        /**
         * `rec-20261117-211530-9f3c1a2b.wav`: sorts by time, carries the first 8
         * characters of the project id so two takes can be told apart, and no
         * character a filesystem would reject.
         */
        fun audioFileName(projectId: String, capturedAt: Long): String =
            "rec-${secondStamp(capturedAt)}-${shortId(projectId)}.wav"

        /** `録音 11-17 21-15-20261117-2115.musicxml` style: the title plus a minute stamp. */
        fun documentFileName(fileName: String, savedAt: Long): String {
            val extension = fileName.substringAfterLast('.', "")
                .lowercase()
                .filter { it.isLetterOrDigit() }
                .ifEmpty { "txt" }
            val base = fileName
                .substringBeforeLast('.', fileName)
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-")
                .trim('-', '.', ' ')
                .ifEmpty { "PianoSheet" }
                .take(MAX_BASE_LENGTH)
            return "$base-${minuteStamp(savedAt)}.$extension"
        }

        /** RIFF/WAVE magic: what the final analysis can actually read. */
        fun looksLikeWav(header: ByteArray): Boolean =
            header.size >= WAV_HEADER_BYTES &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"

        private fun shortId(projectId: String): String =
            projectId.filter { it.isLetterOrDigit() }.lowercase().take(8).ifEmpty { "take" }

        private fun secondStamp(time: Long): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(time))

        private fun minuteStamp(time: Long): String =
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(time))
    }
}
