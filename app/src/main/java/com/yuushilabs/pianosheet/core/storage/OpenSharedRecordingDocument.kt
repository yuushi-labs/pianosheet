package com.yuushilabs.pianosheet.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * `OpenDocument` that starts the system picker in `Music/PianoSheet`, where the
 * takes saved by this app live.  Without the hint the picker opens on "最近"
 * and the user has to walk to the folder after a reinstall.
 */
class OpenSharedRecordingDocument : ActivityResultContracts.OpenDocument() {

    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        initialFolderUri()?.let { uri ->
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
        return intent
    }

    companion object {
        private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

        /** `content://com.android.externalstorage.documents/document/primary:Music/PianoSheet` */
        fun initialFolderUri(): Uri? = runCatching {
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_PROVIDER,
                "primary:" + SharedAudioStorage.MUSIC_FOLDER,
            )
        }.getOrNull()
    }
}
