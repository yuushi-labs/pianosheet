package com.yuushilabs.pianosheet.core.update

import android.content.Intent
import java.io.File

/**
 * Handing a downloaded APK to the system installer (spec: アプリ内アップデート).
 * The Android implementation is
 * [com.yuushilabs.pianosheet.data.update.AndroidApkInstaller].
 */
interface ApkInstaller {

    /** Whether this source may install packages (`REQUEST_INSTALL_PACKAGES` granted by the user). */
    fun canInstallPackages(): Boolean

    /** The "install unknown apps" screen for this app, where that switch lives. */
    fun unknownSourcesSettingsIntent(): Intent

    /** Opens the system installer on [apk], readable through the FileProvider. */
    fun installIntent(apk: File): Intent
}
