package com.yuushilabs.pianosheet.data.update

import com.yuushilabs.pianosheet.core.update.ApkDownloader
import com.yuushilabs.pianosheet.core.update.ApkFiles
import com.yuushilabs.pianosheet.core.update.GithubReleaseJson
import com.yuushilabs.pianosheet.core.update.ReleaseFetcher
import com.yuushilabs.pianosheet.core.update.UpdateDecision
import com.yuushilabs.pianosheet.core.update.UpdateInfo
import kotlinx.coroutines.CancellationException
import java.io.File

/** What one look at the latest release concluded. */
sealed interface CheckOutcome {

    /** A newer release with an APK; [skipped] when the user already said 後で to it. */
    data class Available(val info: UpdateInfo, val skipped: Boolean) : CheckOutcome

    /** The running build is the newest. */
    data object UpToDate : CheckOutcome

    /** An automatic check inside the cool-down window: nothing was asked. */
    data object Throttled : CheckOutcome

    /** The check could not be completed; the message is for a manual check only. */
    data class Unavailable(val reason: String) : CheckOutcome
}

/**
 * The update policy: ask GitHub at most once per cool-down, offer only what is
 * genuinely newer, and keep the APK in `cache/updates/` until it is installed.
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
    private val minCheckIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Asks for the latest release.  A manual check ignores the cool-down and
     * reports why it failed; an automatic one stays quiet and simply does not
     * repeat within [minCheckIntervalMs].
     */
    suspend fun check(manual: Boolean): CheckOutcome {
        if (!manual) {
            val last = memo.lastCheckedAt()
            if (last != null && clock() - last in 0 until minCheckIntervalMs) {
                return CheckOutcome.Throttled
            }
        }

        val body = fetcher.fetchLatest().getOrElse { error ->
            return CheckOutcome.Unavailable(error.message ?: "update check failed")
        }
        val release = GithubReleaseJson.parse(body)
            ?: return CheckOutcome.Unavailable("release JSON を読めませんでした")
        memo.recordCheck(clock())

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

    suspend fun skip(version: String) = memo.skip(version)

    suspend fun clearSkip() = memo.clearSkip()

    companion object {
        /** Auto checks run on launch, but not more often than this. */
        const val DEFAULT_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
