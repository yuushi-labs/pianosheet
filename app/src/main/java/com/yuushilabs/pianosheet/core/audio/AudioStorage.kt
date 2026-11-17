package com.yuushilabs.pianosheet.core.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * App-specific storage for the captured and imported audio (spec §9.1).
 *
 * The raw WAV lives here for the whole life of the project: even when the live
 * result is wrong, the final analysis can start again from this file.
 */
class AudioStorage(private val context: Context) {

    private val recordingsDir: File
        get() = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Where the working copies of every take live (`files/recordings`). */
    val directory: File get() = recordingsDir

    fun newRecordingFile(projectId: String): File = File(recordingsDir, "rec-$projectId.wav")

    fun importFromUri(uri: Uri, projectId: String): File? {
        val name = displayName(uri) ?: "import-$projectId.wav"
        val extension = name.substringAfterLast('.', "wav").lowercase()
        val safeExtension = extension.filter { it.isLetterOrDigit() }.ifEmpty { "wav" }
        val target = File(recordingsDir, "import-$projectId.$safeExtension")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists() && target.length() > 0) target else null
        } catch (error: Exception) {
            Log.w(TAG, "import failed", error)
            null
        }
    }

    fun displayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        } catch (error: Exception) {
            null
        }
    }

    fun delete(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) file.delete()
        } catch (error: Exception) {
            Log.w(TAG, "delete failed", error)
        }
    }

    companion object {
        private const val TAG = "AudioStorage"
    }
}
