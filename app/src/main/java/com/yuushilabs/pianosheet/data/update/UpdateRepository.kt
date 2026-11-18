package com.yuushilabs.pianosheet.data.update

import com.yuushilabs.pianosheet.core.update.ApkDownloader
import com.yuushilabs.pianosheet.core.update.ApkFiles
import com.yuushilabs.pianosheet.core.update.AppVersion
import com.yuushilabs.pianosheet.core.update.GithubRelease
import com.yuushilabs.pianosheet.core.update.GithubReleaseJson
import com.yuushilabs.pianosheet.core.update.ReleaseFetcher
import com.yuushilabs.pianosheet.core.update.UpdateDecision
import com.yuushilabs.pianosheet.core.update.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What one look at the latest release concluded. */
sealed interface CheckOutcome {

    /** A newer release with an APK; [skipped] when the user already said 後で to it. */
    data class Available(val info: UpdateInfo, val skipped: Boolean) : CheckOutcome

    /** The running build is the newest. */
    data object UpToDate : CheckOutcome

    /** Another check finished within the debounce window: nothing was asked. */
    data object Throttled : CheckOutcome

    /** The check could not be completed; the message is for a manual check only. */
    data class Unavailable(val reason: String) : CheckOutcome
}

/**
 * The update policy: ask GitHub on every launch and every return to the
 * foreground (0.4.7 — the old six-hour cool-down meant a second launch almost
 * never asked), offer only what is genuinely newer, keep the APK in
 * `cache/updates/` until it is installed, and delete it once the version that
 * carries it is running.
 *
 * The request is conditional on the `ETag` remembered from the previous answer:
 * GitHub does not count a 304 (unchanged release) against the 60 requests an
 * hour an unauthenticated client gets, so an app that is opened all day still
 * costs almost nothing.  Only [DEFAULT_AUTO_CHECK_DEBOUNCE_MS] of double-fire
 * protection remains, which keeps a resume right after a launch from asking
 * twice.
 *
 * Nothing here touches the network directly — the fetcher and the downloader
 * are injected — so the whole flow runs against fakes in a unit test.
 */
class UpdateRepository(
    private val fetcher: ReleaseFetcher,
    private val downloader: ApkDownloader,
    private val memo: UpdateMemo,
    private val apkDirectory: File,
    val currentVersionName: String,
    private val currentVersionCode: Int,
    private val minCheckIntervalMs: Long = DEFAULT_AUTO_CHECK_DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Asks for the latest release.  A manual check always goes out and reports
     * why it failed; an automatic one only skips a repeat within
     * [minCheckIntervalMs] and stays quiet when the network or GitHub fails.
     */
    suspend fun check(manual: Boolean): CheckOutcome {
        if (!manual) {
            val last = memo.lastCheckedAt()
            if (last != null && clock() - last in 0 until minCheckIntervalMs) {
                return CheckOutcome.Throttled
            }
        }

        val rememberedEtag = memo.releaseEtag()
        val response = fetcher.fetchLatest(rememberedEtag).getOrElse { error ->
            // Remembered as a check so a flaky network is not retried on every
            // single resume; the message is only for a manual check.
            memo.recordCheck(clock())
            return CheckOutcome.Unavailable(error.message ?: "update check failed")
        }

        if (response.notModified) {
            // Nothing changed since the last answer: decide from the remembered
            // document, so a version the user has not installed yet is still
            // offered (and one they said 後で to is still only in settings).
            memo.recordCheck(clock(), etag = response.etag ?: rememberedEtag)
            val body = memo.releaseBody()
                ?: return CheckOutcome.Unavailable("前回のリリース情報がありません")
            val release = GithubReleaseJson.parse(body)
                ?: return CheckOutcome.Unavailable("release JSON を読めませんでした")
            return decide(release)
        }

        val body = response.body
            ?: return CheckOutcome.Unavailable("release JSON を読めませんでした")
        val release = GithubReleaseJson.parse(body)
            ?: return CheckOutcome.Unavailable("release JSON を読めませんでした")
        memo.recordCheck(clock(), etag = response.etag, body = body)
        return decide(release)
    }

    private suspend fun decide(release: GithubRelease): CheckOutcome {
        val info = UpdateDecision.decide(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            release = release,
        ) ?: return CheckOutcome.UpToDate

        return CheckOutcome.Available(info = info, skipped = memo.skippedVersion() == info.versionName)
    }

    /** Where this version's APK lives; a finished download is reused. */
    fun apkFileFor(info: UpdateInfo): File =
        File(apkDirectory, "pianosheet-${info.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")

    /** The APK already on disk for [info], when a previous attempt finished it. */
    fun cachedApk(info: UpdateInfo): File? {
        val file = apkFileFor(info)
        return file.takeIf { ApkFiles.sizeMatches(it, info.apkSizeBytes) && ApkFiles.looksLikeApk(it) }
    }

    suspend fun download(info: UpdateInfo, onProgress: (Long, Long) -> Unit): Result<File> {
        // Old downloads and half-written partials go before the new transfer.
        pruneStaleApks()
        cachedApk(info)?.let { return Result.success(it) }
        return try {
            Result.success(
                downloader.download(
                    url = info.apkUrl,
                    target = apkFileFor(info),
                    expectedBytes = info.apkSizeBytes,
                    onProgress = onProgress,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * Deletes what a finished or abandoned upgrade left in the download folder,
     * and returns what it removed.
     *
     * The APK of the update is ~190 MB and `cache/updates/` is only emptied by
     * Android when the system needs the space, so after the new version starts
     * the file is still there.  Running this on every cold start (and before a
     * download) removes:
     *  - the APK of the running version and every older one (an upgrade that
     *    has happened, or one this build can no longer install),
     *  - `.part` files, which are the leftovers of a cancelled or failed transfer,
     *  - anything else in the folder that is not a download of this app.
     *
     * An APK *newer* than the running build is kept: it is an update the user
     * downloaded and has not installed yet, and it saves the next 190 MB.
     */
    suspend fun pruneStaleApks(): List<File> = withContext(ioDispatcher) {
        val running = AppVersion.parse(currentVersionName)
        val removed = mutableListOf<File>()
        val files = apkDirectory.listFiles() ?: return@withContext removed
        for (file in files) {
            if (!file.isFile) continue
            if (isStale(file, running) && file.delete()) removed += file
        }
        removed
    }

    /** Whether [file] can go: see [pruneStaleApks] for the rules. */
    private fun isStale(file: File, running: AppVersion?): Boolean = when {
        file.name.endsWith(PART_SUFFIX) -> true

        file.name.endsWith(".apk", ignoreCase = true) -> {
            val downloaded = AppVersion.parse(file.name.removeSuffix(".apk"))
            // nameless junk, or a version this build has (or should have) replaced.
            downloaded == null || (running != null && downloaded <= running)
        }

        else -> clock() - file.lastModified() > UNKNOWN_FILE_TTL_MS
    }

    suspend fun skip(version: String) = memo.skip(version)

    suspend fun clearSkip() = memo.clearSkip()

    companion object {
        /**
         * Only back-to-back launches are debounced (a resume right after the
         * launch check, a second activity in the same second); every real
         * return to the app asks again.
         */
        const val DEFAULT_AUTO_CHECK_DEBOUNCE_MS = 20_000L

        private const val PART_SUFFIX = ".part"

        /** An unrecognised file in the folder is junk; keep it a day before deleting. */
        private const val UNKNOWN_FILE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
