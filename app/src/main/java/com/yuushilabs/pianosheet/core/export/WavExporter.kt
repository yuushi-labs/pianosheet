package com.yuushilabs.pianosheet.core.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Saves and shares the untouched recording (spec §8, Phase 2 request "生録音ファイルもほしい").
 *
 * Saving goes through the Storage Access Framework, so the user picks the folder
 * (Downloads, Drive, an SD card) themselves and the app never needs a storage
 * permission.  Sharing uses a FileProvider URI, which grants the receiving app
 * read access to exactly this one file.
 */
class WavExporter(private val context: Context) {

    /** "録音 11/16 21:03" -> "録音 11-16 21-03-raw.wav" */
    fun suggestedFileName(title: String, extension: String = "wav"): String {
        val safeExtension = extension
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "wav" }
        val base = title
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .replace("/", "-")
            .trim()
            .ifEmpty { "pianosheet" }
        val withoutExtension = base.removeSuffix(".$safeExtension")
        return "$withoutExtension-raw.$safeExtension"
    }

    /** Copies [source] into the location the user chose. Returns the byte count. */
    fun save(source: File, target: Uri): Result<Long> = runCatching {
        require(source.exists()) { "録音ファイルが見つかりません" }
        val resolver = context.contentResolver
        val bytes = resolver.openOutputStream(target, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("保存先を開けませんでした")
        if (bytes <= 0) throw IllegalStateException("保存できませんでした（0 バイト）")
        bytes
    }.onFailure { Log.w(TAG, "WAV save failed", it) }

    /** Share sheet for the raw take, or null when the file is missing. */
    fun shareIntent(source: File, title: String): Intent? {
        if (!source.exists()) return null
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                source,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (source.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, "生録音(WAV)を共有").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            Log.w(TAG, "share intent failed", error)
            null
        }
    }

    companion object {
        private const val TAG = "WavExporter"
    }
}
