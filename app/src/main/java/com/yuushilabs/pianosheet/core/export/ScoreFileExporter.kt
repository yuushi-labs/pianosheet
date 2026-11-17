package com.yuushilabs.pianosheet.core.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.yuushilabs.pianosheet.domain.export.MidiExporter
import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.score.Score
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The score exports of spec §8: MusicXML and MIDI, written to the app cache and
 * then either copied where the user picked (SAF) or handed to the share sheet
 * (FileProvider).
 *
 * The heavy lifting is the pure [MusicXmlExporter] / [MidiExporter]; this class
 * only knows about Android files, so the formats stay unit-testable.
 */
class ScoreFileExporter(private val context: Context) {

    /** `<title>-20261117-2115.musicxml` in cache/exports. */
    fun musicXmlFile(score: Score): Result<File> = runCatching {
        val xml = MusicXmlExporter.toMusicXml(score)
        writeFile("${baseName(score)}-${stamp()}.${MusicXmlExporter.FILE_EXTENSION}", xml.toByteArray(Charsets.UTF_8))
    }.onFailure { Log.w(TAG, "MusicXML write failed", it) }

    /** `<title>-20261117-2115.mid` in cache/exports. */
    fun midiFile(score: Score): Result<File> = runCatching {
        writeFile(
            "${baseName(score)}-${stamp()}.${MidiExporter.FILE_EXTENSION}",
            MidiExporter.toBytes(score),
        )
    }.onFailure { Log.w(TAG, "MIDI write failed", it) }

    /** Name offered in the save dialog, extension included. */
    fun suggestedFileName(score: Score, extension: String): String =
        "${baseName(score)}.$extension"

    /** Copies [source] to the document the user chose. Returns the byte count. */
    fun save(source: File, target: Uri): Result<Long> = runCatching {
        require(source.exists()) { "書き出すファイルが見つかりません" }
        val bytes = context.contentResolver.openOutputStream(target, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("保存先を開けませんでした")
        if (bytes <= 0) throw IllegalStateException("保存できませんでした（0 バイト）")
        bytes
    }.onFailure { Log.w(TAG, "score export save failed", it) }

    /** Share sheet for an exported score file, or null when it is missing. */
    fun shareIntent(source: File, mimeType: String, title: String, chooserTitle: String): Intent? {
        if (!source.exists()) return null
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                source,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            Log.w(TAG, "score export share failed", error)
            null
        }
    }

    private fun writeFile(name: String, bytes: ByteArray): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        // Old exports in the cache are only ever shared once; drop stale copies,
        // but leave anything recent alone: a share sheet that is still open must
        // find the file it was handed.
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        directory.listFiles()?.forEach { old -> if (old.isFile && old.lastModified() < cutoff) old.delete() }
        val file = File(directory, name)
        file.writeBytes(bytes)
        return file
    }

    /** File-name safe title: no path separators, no characters a FAT card rejects. */
    private fun baseName(score: Score): String {
        val cleaned = score.title
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
        return cleaned.ifEmpty { "PianoSheet" }.take(60)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    companion object {
        private const val TAG = "ScoreFileExporter"
        private const val STALE_AFTER_MS = 10 * 60 * 1000L
        const val WAV_MIME_TYPE = "audio/wav"
    }
}
