package com.yuushilabs.pianosheet.app

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.yuushilabs.pianosheet.core.audio.AudioStorage
import com.yuushilabs.pianosheet.core.export.ScoreFileExporter
import com.yuushilabs.pianosheet.core.export.WavExporter
import com.yuushilabs.pianosheet.core.inference.BasicPitchConstants
import com.yuushilabs.pianosheet.core.inference.BasicPitchDetector
import com.yuushilabs.pianosheet.core.inference.BasicPitchModel
import com.yuushilabs.pianosheet.core.inference.PtiFinalEngine
import com.yuushilabs.pianosheet.core.inference.PtiOnnxModel
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

    /**
     * PTI-ONNX for the final pass.  The 94 MB session is built on the analysis
     * thread (never at app start) and released again when the container closes,
     * because its arena peaks around 1.1 GB.
     */
    private val ptiModel: PtiOnnxModel? by lazy {
        try {
            PtiOnnxModel(appContext)
        } catch (error: Throwable) {
            // A missing/corrupt asset or a failed session must not take the app down;
            // the final analysis then stays on Basic Pitch.
            Log.w("AppContainer", "PTI session unavailable: ${error.message}")
            null
        }
    }

    /**
     * The final analysis: PTI-ONNX over the whole take, the Basic Pitch window
     * pass kept as the detector and the fallback.
     */
    val finalTranscriber: FinalTranscriber by lazy {
        val basicPitch = BasicPitchDetector(
            model = basicPitchModel,
            onsetThreshold = BasicPitchConstants.FINAL_ONSET_THRESHOLD,
        )
        val engine = ptiModel?.let { model ->
            PtiFinalEngine(model = model, canRun = { freeMemoryMb() >= MIN_PTI_FREE_MB })
        }
        FinalTranscriber(detector = basicPitch, finalEngine = engine)
    }

    /** Free device RAM, or [Long.MAX_VALUE] when the platform cannot say. */
    private fun freeMemoryMb(): Long {
        return try {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem / (1024L * 1024L)
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
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
        try {
            ptiModel?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * Free RAM the PTI pass wants.  Its peak measured 1.1–1.2 GB on the
         * benchmark box (arena on, 2 threads); below this the Basic Pitch pass
         * is used instead so a 3–4 GB phone is not killed mid-analysis.
         */
        const val MIN_PTI_FREE_MB = 1_400L
    }
}
