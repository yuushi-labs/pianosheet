package com.yuushilabs.pianosheet.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * [SharedMediaGateway] on top of `ContentResolver`/MediaStore.
 *
 * Everything here is a plain insert into a public collection: no storage
 * permission is involved on API 29+ (the app only ever writes files it owns),
 * and the row belongs to the media store rather than to the app, so it stays
 * after an uninstall.  A row is inserted as `IS_PENDING` and only published
 * once the bytes are complete, so a half-written file is never visible in the
 * user's music folder.
 */
class AndroidSharedMediaGateway(private val context: Context) : SharedMediaGateway {

    override fun write(request: SharedWriteRequest, openSource: () -> InputStream): SharedFile {
        val resolver = context.contentResolver
        val collectionUri = when (request.collection) {
            SharedCollection.MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            SharedCollection.DOCUMENT -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, request.relativeFolder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collectionUri, values)
            ?: throw IOException("共有フォルダに保存先を作れませんでした")
        try {
            val written = resolver.openOutputStream(uri, "w")?.use { output ->
                openSource().use { input -> input.copyTo(output, 1 shl 16) }
            } ?: throw FileNotFoundException("保存先を開けませんでした")
            if (written <= 0L) throw IOException("保存できませんでした（0 バイト）")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            // The store may rename on a name collision ("rec-... (1).wav"), so the
            // real name and folder are read back from the row we just wrote.
            val fileName = queryString(uri, MediaStore.MediaColumns.DISPLAY_NAME) ?: request.fileName
            val folder = queryString(uri, MediaStore.MediaColumns.RELATIVE_PATH)
                ?.trimEnd('/')
                ?: request.relativeFolder
            val bytes = queryLong(uri, MediaStore.MediaColumns.SIZE) ?: written
            return SharedFile(
                uri = uri.toString(),
                displayPath = SharedAudioStorage.displayPath(folder, fileName),
                fileName = fileName,
                bytes = bytes,
                collection = request.collection,
            )
        } catch (error: Exception) {
            // Leave nothing behind: a pending row would be invisible but still hold space.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    override fun openInput(uri: String): InputStream? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))
    } catch (error: Exception) {
        null
    }

    override fun displayName(uri: String): String? = try {
        context.contentResolver
            .query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (error: Exception) {
        null
    }

    private fun queryString(uri: Uri, column: String): String? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (error: Exception) {
        null
    }

    private fun queryLong(uri: Uri, column: String): Long? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    } catch (error: Exception) {
        null
    }
}
