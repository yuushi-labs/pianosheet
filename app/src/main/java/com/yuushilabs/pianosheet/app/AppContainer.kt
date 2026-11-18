package com.yuushilabs.pianosheet.app

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.yuushilabs.pianosheet.BuildConfig
import com.yuushilabs.pianosheet.core.audio.AudioStorage
import com.yuushilabs.pianosheet.core.export.ScoreFileExporter
import com.yuushilabs.pianosheet.core.export.WavExporter
import com.yuushilabs.pianosheet.core.inference.BasicPitchConstants
import com.yuushilabs.pianosheet.core.inference.BasicPitchDetector
import com.yuushilabs.pianosheet.core.inference.BasicPitchModel
import com.yuushilabs.pianosheet.core.inference.PtiFinalEngine
import com.yuushilabs.pianosheet.core.inference.PtiOnnxModel
import com.yuushilabs.pianosheet.core.inference.PtiWindowModel
import com.yuushilabs.pianosheet.core.storage.AndroidSharedMediaGateway
import com.yuushilabs.pianosheet.core.storage.SharedAudioStorage
import com.yuushilabs.pianosheet.core.update.HttpApkDownloader
import com.yuushilabs.pianosheet.core.update.HttpReleaseFetcher
import com.yuushilabs.pianosheet.data.ProjectRepositoryImpl
import com.yuushilabs.pianosheet.data.SettingsRepository
import com.yuushilabs.pianosheet.data.db.AppDatabase
import com.yuushilabs.pianosheet.data.update.AndroidApkInstaller
import com.yuushilabs.pianosheet.data.update.DataStoreUpdateMemo
import com.yuushilabs.pianosheet.data.update.UpdateRepository
import com.yuushilabs.pianosheet.domain.final.EngineUnavailableException
import com.yuushilabs.pianosheet.domain.final.FinalTranscriber
import com.yuushilabs.pianosheet.domain.repository.ProjectRepository
import com.yuushilabs.pianosheet.feature.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File

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

    /**
     * The in-app updater (0.4.5).  One instance for the process, so a check or a
     * download keeps going while the user moves between home and settings.
     * The APK lands in `cache/updates/`, which `res/xml/file_paths.xml` exports
     * to the installer.
     */
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val updateController: UpdateController by lazy {
        val userAgent = "PianoSheet-Android/${BuildConfig.VERSION_NAME}"
        val repository = UpdateRepository(
            fetcher = HttpReleaseFetcher(userAgent = userAgent),
            downloader = HttpApkDownloader(userAgent = userAgent),
            memo = DataStoreUpdateMemo(appContext),
            apkDirectory = File(appContext.cacheDir, "updates"),
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
        )
        UpdateController(
            repository = repository,
            installer = AndroidApkInstaller(appContext),
            scope = updateScope,
        )
    }

    val audioStorage: AudioStorage by lazy { AudioStorage(appContext) }

    val wavExporter: WavExporter by lazy { WavExporter(appContext) }

    /**
     * Recordings and score exports written to `Music/PianoSheet` and
     * `Documents/PianoSheet`, which survive an uninstall (0.4.1).
     */
    val sharedAudioStorage: SharedAudioStorage by lazy {
        SharedAudioStorage(AndroidSharedMediaGateway(appContext))
    }

    /** MusicXML / MIDI files for the finished score (spec §8). */
    val scoreFileExporter: ScoreFileExporter by lazy { ScoreFileExporter(appContext) }

    /** Loaded lazily on the inference dispatcher; the session costs a few hundred ms. */
    val basicPitchModel: BasicPitchModel by lazy { BasicPitchModel(appContext) }

    /**
     * The PTI session, kept only while an analysis runs.
     *
     * Building it costs 0.2 s and copying the 94 MB asset out of the APK a little
     * more; holding it between analyses costs ~1 GB of resident memory, so it is
     * closed as soon as the notes are out and rebuilt for the next analysis.
     */
    private var ptiModel: PtiOnnxModel? = null
    private var ptiModelFailed = false

    @Synchronized
    private fun obtainPtiModel(): PtiOnnxModel {
        ptiModel?.let { return it }
        if (ptiModelFailed) {
            throw EngineUnavailableException("PTI モデルを読み込めませんでした")
        }
        return try {
            PtiOnnxModel(appContext).also { ptiModel = it }
        } catch (error: Throwable) {
            // A missing/corrupt asset or a failed session must not take the app
            // down; the final analysis then stays on Basic Pitch.
            Log.w("AppContainer", "PTI session unavailable: ${error.message}")
            ptiModelFailed = true
            throw EngineUnavailableException("PTI モデルを読み込めませんでした", error)
        }
    }

    @Synchronized
    private fun releasePtiModel(model: PtiWindowModel) {
        if (ptiModel === model) {
            try {
                (model as? Closeable)?.close()
            } catch (_: Exception) {
            }
            ptiModel = null
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
        val engine = PtiFinalEngine(
            modelProvider = { obtainPtiModel() },
            releaseModel = { model -> releasePtiModel(model) },
            canRun = { freeMemoryMb() >= MIN_PTI_FREE_MB },
        )
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
        updateScope.cancel()
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
        ptiModel = null
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
