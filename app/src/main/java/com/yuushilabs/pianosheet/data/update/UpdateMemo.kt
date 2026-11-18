package com.yuushilabs.pianosheet.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * What the update check remembers between launches: when it last asked GitHub,
 * the `ETag` and the document of the release it last read (so an unchanged
 * release can be answered from the 304 without asking again), and which version
 * the user said "後で" to.  Kept behind an interface so the repository's
 * throttling and skip policy can be tested without a device.
 */
interface UpdateMemo {
    suspend fun lastCheckedAt(): Long?

    /** Datastore for the conditional request GitHub answers with 304. */
    suspend fun releaseEtag(): String?

    /** The release document the [releaseEtag] belongs to. */
    suspend fun releaseBody(): String?

    suspend fun skippedVersion(): String?

    /** Records this check; [etag] and [body] are kept only when the server sent them. */
    suspend fun recordCheck(at: Long, etag: String? = null, body: String? = null)

    suspend fun skip(version: String)

    suspend fun clearSkip()
}

private val Context.updateDataStore by preferencesDataStore(name = "app_update")

class DataStoreUpdateMemo(private val context: Context) : UpdateMemo {

    private object Keys {
        val lastCheckedAt = longPreferencesKey("last_checked_at")
        val skippedVersion = stringPreferencesKey("skipped_version")
        val releaseEtag = stringPreferencesKey("release_etag")
        val releaseBody = stringPreferencesKey("release_body")
    }

    override suspend fun lastCheckedAt(): Long? =
        context.updateDataStore.data.first()[Keys.lastCheckedAt]

    override suspend fun releaseEtag(): String? =
        context.updateDataStore.data.first()[Keys.releaseEtag]

    override suspend fun releaseBody(): String? =
        context.updateDataStore.data.first()[Keys.releaseBody]

    override suspend fun skippedVersion(): String? =
        context.updateDataStore.data.first()[Keys.skippedVersion]

    override suspend fun recordCheck(at: Long, etag: String?, body: String?) {
        context.updateDataStore.edit { prefs ->
            prefs[Keys.lastCheckedAt] = at
            if (etag != null) prefs[Keys.releaseEtag] = etag
            if (body != null) prefs[Keys.releaseBody] = body
        }
    }

    override suspend fun skip(version: String) {
        context.updateDataStore.edit { it[Keys.skippedVersion] = version }
    }

    override suspend fun clearSkip() {
        context.updateDataStore.edit { it.remove(Keys.skippedVersion) }
    }
}
