package com.yuushilabs.pianosheet.data.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 0.4.5: the update policy, checked against fakes so it runs on the JVM — the
 * cool-down, the "後で" skip, the quiet failure of an automatic check, and a
 * download that is only reused once it is a complete APK.
 */
class UpdateRepositoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val apkUrl =
        "https://github.com/yuushi-labs/pianosheet/releases/download/v0.4.5-phase2/" +
            "pianosheet-v0.4.5-phase2-debug.apk"

    @Test
    fun `a newer release comes back as available`() {
        val repository = repository(fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2")))
        val available = runBlocking { repository.check(manual = true) } as CheckOutcome.Available
        assertEquals("0.4.5-phase2", available.info.versionName)
        assertEquals(apkUrl, available.info.apkUrl)
        assertFalse(available.skipped)
    }

    @Test
    fun `the same version comes back as up to date`() {
        val repository = repository(fetcher = FakeReleaseFetcher(releasePayload("0.4.4-phase2")))
        assertEquals(CheckOutcome.UpToDate, runBlocking { repository.check(manual = true) })
    }

    @Test
    fun `a failing request is reported for a manual check`() {
        val repository = repository(fetcher = FakeReleaseFetcher(error = IOException("HTTP 404")))
        assertTrue(runBlocking { repository.check(manual = true) } is CheckOutcome.Unavailable)
    }

    @Test
    fun `a body that is not a release is not an update`() {
        val repository = repository(fetcher = FakeReleaseFetcher("<html>502</html>"))
        assertTrue(runBlocking { repository.check(manual = true) } is CheckOutcome.Unavailable)
    }

    @Test
    fun `an automatic check within the cool-down asks nothing`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val memo = InMemoryUpdateMemo(checkedAt = 1_000L)
        val repository = repository(fetcher = fetcher, memo = memo, now = 1_500L)
        assertEquals(CheckOutcome.Throttled, runBlocking { repository.check(manual = false) })
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun `an automatic check after the cool-down asks again`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val now = 1_000L + UpdateRepository.DEFAULT_CHECK_INTERVAL_MS + 1
        val memo = InMemoryUpdateMemo(checkedAt = 1_000L)
        val repository = repository(fetcher = fetcher, memo = memo, now = now)
        assertTrue(runBlocking { repository.check(manual = false) } is CheckOutcome.Available)
        assertEquals(now, memo.checkedAt)
    }

    @Test
    fun `a manual check ignores the cool-down`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val repository = repository(
            fetcher = fetcher,
            memo = InMemoryUpdateMemo(checkedAt = 1_000L),
            now = 1_001L,
        )
        assertTrue(runBlocking { repository.check(manual = true) } is CheckOutcome.Available)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `a version the user said 後で to is still available but marked skipped`() {
        val memo = InMemoryUpdateMemo(skipped = "0.4.5-phase2")
        val repository = repository(fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2")), memo = memo)
        val available = runBlocking { repository.check(manual = true) } as CheckOutcome.Available
        assertTrue(available.skipped)
    }

    @Test
    fun `skip and clearSkip are remembered`() {
        val memo = InMemoryUpdateMemo()
        val repository = repository(memo = memo)
        runBlocking {
            repository.skip("0.4.5-phase2")
            assertEquals("0.4.5-phase2", memo.skipped)
            repository.clearSkip()
            assertNull(memo.skipped)
        }
    }

    @Test
    fun `a download lands in the update folder and reports progress`() {
        val downloader = FakeApkDownloader(apkBytes = 3_000_000L)
        val repository = repository(downloader = downloader)
        val info = availableInfo(repository)
        val progress = mutableListOf<Pair<Long, Long>>()

        val file = runBlocking {
            repository.download(info) { bytes, total -> progress.add(bytes to total) }
        }.getOrThrow()

        assertTrue(file.exists())
        assertEquals(repository.apkFileFor(info).absolutePath, file.absolutePath)
        assertEquals(3_000_000L, file.length())
        assertEquals(1, downloader.calls)
        assertEquals(3_000_000L, progress.last().first)
    }

    @Test
    fun `a finished download is reused instead of fetched again`() {
        val downloader = FakeApkDownloader(apkBytes = 3_000_000L)
        val repository = repository(downloader = downloader)
        val info = availableInfo(repository)

        runBlocking { repository.download(info) { _, _ -> } }.getOrThrow()
        runBlocking { repository.download(info) { _, _ -> } }.getOrThrow()

        assertEquals(1, downloader.calls)
        assertNotNull(repository.cachedApk(info))
    }

    @Test
    fun `a file that is not an apk is never offered`() {
        val repository = repository()
        val info = availableInfo(repository)
        val broken = repository.apkFileFor(info)
        broken.parentFile?.mkdirs()
        broken.writeText("<html>rate limited</html>")

        assertNull(repository.cachedApk(info))
    }

    @Test
    fun `a failed download comes back as a failure, not an exception`() {
        val downloader = FakeApkDownloader(failure = IOException("no network"))
        val repository = repository(downloader = downloader)
        val info = availableInfo(repository)
        assertTrue(runBlocking { repository.download(info) { _, _ -> } }.isFailure)
    }

    @Test
    fun `the apk file name follows the version and stays a plain file name`() {
        val repository = repository()
        val info = availableInfo(repository)
        assertEquals("pianosheet-0.4.5-phase2.apk", repository.apkFileFor(info).name)
        assertFalse(repository.apkFileFor(info.copy(versionName = "../../etc/passwd")).name.contains("/"))
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun repository(
        fetcher: FakeReleaseFetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2")),
        downloader: FakeApkDownloader = FakeApkDownloader(),
        memo: UpdateMemo = InMemoryUpdateMemo(),
        now: Long = 10_000L,
    ) = UpdateRepository(
        fetcher = fetcher,
        downloader = downloader,
        memo = memo,
        apkDirectory = File(temporary.root, "updates"),
        currentVersionName = "0.4.4-phase2",
        currentVersionCode = 11,
        minCheckIntervalMs = UpdateRepository.DEFAULT_CHECK_INTERVAL_MS,
        clock = { now },
    )

    private fun availableInfo(repository: UpdateRepository) =
        (runBlocking { repository.check(manual = true) } as CheckOutcome.Available).info
}
