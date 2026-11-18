package com.yuushilabs.pianosheet.feature.update

import android.util.Log
import com.yuushilabs.pianosheet.core.update.ApkInstaller
import com.yuushilabs.pianosheet.core.update.UpdateInfo
import com.yuushilabs.pianosheet.data.update.CheckOutcome
import com.yuushilabs.pianosheet.data.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the home banner and the settings section show. */
sealed interface UpdateUiState {

    /** Nothing asked yet. */
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    /** The running build is the newest one. */
    data object Latest : UpdateUiState

    /**
     * A newer release exists.  [skipped] means the user pressed 後で, so the
     * home banner stays out of the way and only the settings entry mentions it.
     */
    data class Available(val info: UpdateInfo, val skipped: Boolean) : UpdateUiState

    data class Downloading(
        val info: UpdateInfo,
        val bytes: Long,
        val total: Long,
    ) : UpdateUiState {
        /** Null while the size is unknown. */
        val percent: Int?
            get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else null
    }

    /** Downloaded and ready; the screen opens the installer (asking for permission first). */
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateUiState

    /** [manual] failures are shown; an automatic check stays quiet. */
    data class Failed(val message: String, val manual: Boolean) : UpdateUiState
}

/**
 * Ties the update policy to the two screens that show it.  One instance lives
 * in [com.yuushilabs.pianosheet.app.AppContainer], so the check and the download
 * keep running while the user moves between home and settings.
 */
class UpdateController(
    private val repository: UpdateRepository,
    private val installer: ApkInstaller,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val currentVersionName: String get() = repository.currentVersionName

    private var work: Job? = null

    /** The download folder has been tidied since the process started. */
    private var prunedThisLaunch = false

    /**
     * Called on every launch and every return to the foreground (0.4.7).  Each
     * one asks GitHub, so opening the app is enough and the 確認 button is only
     * a fallback: [UpdateRepository] only skips a second check seconds after
     * the last one.
     */
    fun onForeground() {
        val cleanup = !prunedThisLaunch
        prunedThisLaunch = true
        check(manual = false, cleanupFirst = cleanup)
    }

    fun checkNow() {
        check(manual = true)
    }

    private fun check(manual: Boolean, cleanupFirst: Boolean = false) {
        if (work?.isActive == true) return
        work = scope.launch {
            if (cleanupFirst) {
                // The APK of the version that is now running is still in
                // cache/updates/ (~190 MB): delete it and older leftovers.
                try {
                    repository.pruneStaleApks()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.i(TAG, "update cleanup skipped: ${error.message}")
                }
            }
            // A quiet re-check must not take an offer, a download or the
            // install button off the screen while the user may be looking at it.
            val shown = _state.value
            val keepShown = !manual && when (shown) {
                is UpdateUiState.Available,
                is UpdateUiState.Downloading,
                is UpdateUiState.Ready,
                -> true

                else -> false
            }
            if (!keepShown) _state.value = UpdateUiState.Checking
            val outcome = try {
                repository.check(manual)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CheckOutcome.Unavailable(error.message ?: "update check failed")
            }
            _state.value = when (outcome) {
                is CheckOutcome.Available ->
                    UpdateUiState.Available(outcome.info, outcome.skipped)

                CheckOutcome.UpToDate -> UpdateUiState.Latest
                // A check inside the debounce window changes nothing: keep
                // whatever the screen already shows instead of flashing a state.
                CheckOutcome.Throttled -> if (_state.value is UpdateUiState.Checking) {
                    UpdateUiState.Idle
                } else {
                    _state.value
                }

                is CheckOutcome.Unavailable -> {
                    Log.i(TAG, "update check unavailable: ${outcome.reason}")
                    if (keepShown) {
                        _state.value
                    } else {
                        UpdateUiState.Failed("アップデートを確認できませんでした", manual)
                    }
                }
            }
        }
    }

    /** 今すぐ更新: download the APK, reporting progress. */
    fun downloadNow() {
        val available = _state.value as? UpdateUiState.Available ?: return
        if (work?.isActive == true) return
        val info = available.info
        work = scope.launch {
            _state.value = UpdateUiState.Downloading(info, bytes = 0, total = info.apkSizeBytes)
            val result = repository.download(info) { bytes, total ->
                _state.value = UpdateUiState.Downloading(info, bytes, total)
            }
            _state.value = result.fold(
                onSuccess = { apk -> UpdateUiState.Ready(info, apk) },
                onFailure = { error ->
                    Log.w(TAG, "APK download failed", error)
                    UpdateUiState.Failed("ダウンロードできませんでした", manual = true)
                },
            )
        }
    }

    /** キャンセル: stop the transfer and go back to "there is an update". */
    fun cancelDownload() {
        val current = _state.value as? UpdateUiState.Downloading ?: return
        work?.cancel()
        work = null
        _state.value = UpdateUiState.Available(current.info, skipped = false)
    }

    /** 後で: this version stops announcing itself on the home screen. */
    fun skip() {
        val available = _state.value as? UpdateUiState.Available ?: return
        _state.value = available.copy(skipped = true)
        scope.launch { repository.skip(available.info.versionName) }
    }

    /** The `Ready` file, for the screen that is about to open the installer. */
    fun apkToInstall(): File? = (_state.value as? UpdateUiState.Ready)?.apk

    fun canInstallPackages(): Boolean = installer.canInstallPackages()

    fun unknownSourcesSettingsIntent() = installer.unknownSourcesSettingsIntent()

    fun installIntent(apk: File) = installer.installIntent(apk)

    /**
     * The installer has been opened (or the user left it).  The APK stays on
     * disk — the package installer may still be reading it — but the banner goes.
     */
    fun installStarted() {
        val ready = _state.value as? UpdateUiState.Ready ?: return
        _state.value = UpdateUiState.Available(ready.info, skipped = true)
        scope.launch { repository.skip(ready.info.versionName) }
    }

    /** Manual check from settings after a failure clears the message. */
    fun dismissFailure() {
        if (_state.value is UpdateUiState.Failed) _state.value = UpdateUiState.Idle
    }

    private companion object {
        const val TAG = "UpdateController"
    }
}
