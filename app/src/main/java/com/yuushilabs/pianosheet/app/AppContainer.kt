package com.yuushilabs.pianosheet.app

import android.content.Context
import androidx.room.Room
import com.yuushilabs.pianosheet.core.audio.AudioStorage
import com.yuushilabs.pianosheet.core.export.ScoreFileExporter
import com.yuushilabs.pianosheet.core.export.WavExporter
import com.yuushilabs.pianosheet.core.inference.BasicPitchDetector
import com.yuushilabs.pianosheet.core.inference.BasicPitchModel
import com.yuushilabs.pianosheet.data.ProjectRepositoryImpl
import com.yuushilabs.pianosheet.data.SettingsRepository
import com.yuushilabs.pianosheet.data.db.AppDatabase
import com.yuushilabs.pianosheet.domain.final.FinalTranscriber
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
            // Every schema bump ships an explicit migration: a Phase 1 recording
            // must survive the Phase 2 update (spec §5.7).
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
    }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepositoryImpl(
            projectDao = database.projectDao(),
            recordingAssetDao = database.recordingAssetDao(),
            noteEventDao = database.noteEventDao(),
            scoreDao = database.scoreDao(),
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val audioStorage: AudioStorage by lazy { AudioStorage(appContext) }

    val wavExporter: WavExporter by lazy { WavExporter(appContext) }

    /** MusicXML / MIDI files for the finished score (spec §8). */
    val scoreFileExporter: ScoreFileExporter by lazy { ScoreFileExporter(appContext) }

    /** Loaded lazily on the inference dispatcher; the session costs a few hundred ms. */
    val basicPitchModel: BasicPitchModel by lazy { BasicPitchModel(appContext) }

    /** The final analysis shares the one ONNX session with the live path. */
    val finalTranscriber: FinalTranscriber by lazy {
        FinalTranscriber(BasicPitchDetector(basicPitchModel))
    }

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
