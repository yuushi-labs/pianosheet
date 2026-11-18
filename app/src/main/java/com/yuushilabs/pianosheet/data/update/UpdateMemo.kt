package com.yuushilabs.pianosheet.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * What the update check remembers between launches: when it last asked GitHub,
 * and which version the user said "後で" to.  Kept behind an interface so the
 * repository's throttling and skip policy can be tested without a device.
 */
interface UpdateMemo {
    suspend fun lastCheckedAt(): Long?

    suspend fun skippedVersion(): String?

    suspend fun recordCheck(at: Long)

    suspend fun skip(version: String)

    suspend fun clearSkip()
}

private val Context.updateDataStore by preferencesDataStore(name = "app_update")

class DataStoreUpdateMemo(private val context: Context) : UpdateMemo {

    private object Keys {
        val lastCheckedAt = longPreferencesKey("last_checked_at")
        val skippedVersion = stringPreferencesKey("skipped_version")
    }

    override suspend fun lastCheckedAt(): Long? =
        context.updateDataStore.data.first()[Keys.lastCheckedAt]

    override suspend fun skippedVersion(): String? =
        context.updateDataStore.data.first()[Keys.skippedVersion]

    override suspend fun recordCheck(at: Long) {
        context.updateDataStore.edit { it[Keys.lastCheckedAt] = at }
    }

    override suspend fun skip(version: String) {
        context.updateDataStore.edit { it[Keys.skippedVersion] = version }
    }

    override suspend fun clearSkip() {
        context.updateDataStore.edit { it.remove(Keys.skippedVersion) }
    }
}
