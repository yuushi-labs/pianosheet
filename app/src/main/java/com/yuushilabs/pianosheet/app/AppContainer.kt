package com.yuushilabs.pianosheet.app

import android.content.Context
import androidx.room.Room
import com.yuushilabs.pianosheet.core.audio.AudioStorage
import com.yuushilabs.pianosheet.core.inference.BasicPitchModel
import com.yuushilabs.pianosheet.data.ProjectRepositoryImpl
import com.yuushilabs.pianosheet.data.SettingsRepository
import com.yuushilabs.pianosheet.data.db.AppDatabase
import com.yuushilabs.pianosheet.domain.repository.ProjectRepository
import java.io.Closeable

/**
 * Hand-rolled dependency container: small enough to avoid a DI framework in the
 * MVP, and everything long-lived (database, model) is created once per process.
 */
class AppContainer(context: Context) : Closeable {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "pianosheet.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepositoryImpl(
            projectDao = database.projectDao(),
            recordingAssetDao = database.recordingAssetDao(),
            noteEventDao = database.noteEventDao(),
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val audioStorage: AudioStorage by lazy { AudioStorage(appContext) }

    /** Loaded lazily on the inference dispatcher; the session costs a few hundred ms. */
    val basicPitchModel: BasicPitchModel by lazy { BasicPitchModel(appContext) }

    override fun close() {
        try {
            database.close()
        } catch (_: Exception) {
        }
        try {
            basicPitchModel.close()
        } catch (_: Exception) {
        }
    }
}
