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
 * The update policy, checked against fakes so it runs on the JVM: the 0.4.7
 * rule that every launch asks (no six-hour cool-down), the conditional request
 * that keeps that within GitHub's rate limit, the "後で" skip, the quiet failure
 * of an automatic check, a download that is only reused once it is a complete
 * APK, and the cleanup of what an upgrade leaves in `cache/updates/`.
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
        val repository = repository(fetcher = FakeReleaseFetcher(releasePayload("v0.4.4-phase2")))
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

    // ── 0.4.7: every launch asks, no six-hour cool-down ──────────────────────

    @Test
    fun `an automatic check half an hour after the last one asks again`() {
        // 0.4.5/0.4.6 skipped this for six hours, which is why a second launch
        // never checked for a release.
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val memo = InMemoryUpdateMemo(checkedAt = 1_000L)
        val now = 1_000L + 30L * 60L * 1000L
        val repository = repository(fetcher = fetcher, memo = memo, now = now)

        assertTrue(runBlocking { repository.check(manual = false) } is CheckOutcome.Available)
        assertEquals(1, fetcher.calls)
        assertEquals(now, memo.checkedAt)
    }

    @Test
    fun `two automatic checks seconds apart ask once`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        var now = 1_000L
        val repository = repository(fetcher = fetcher, memo = InMemoryUpdateMemo(), now = { now })

        runBlocking { repository.check(manual = false) }
        now += 3_000L // a resume right after the launch check
        assertEquals(CheckOutcome.Throttled, runBlocking { repository.check(manual = false) })
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `a manual check ignores the debounce`() {
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
    fun `an unchanged release is a 304 and is still offered from the remembered document`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val memo = InMemoryUpdateMemo()
        var now = 10_000L
        val repository = repository(fetcher = fetcher, memo = memo, now = { now })

        runBlocking { repository.check(manual = false) }
        assertNotNull(memo.etag)
        assertNotNull(memo.body)

        now += 60L * 60L * 1000L // an hour later, the app is opened again
        val again = runBlocking { repository.check(manual = false) } as CheckOutcome.Available

        assertEquals(1, fetcher.notModifiedCalls)
        assertEquals("0.4.5-phase2", again.info.versionName)
        assertFalse(again.skipped)
    }

    @Test
    fun `a 304 without a remembered document explains itself instead of claiming to be newest`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val memo = InMemoryUpdateMemo(etag = fetcher.documentEtag, body = null)
        val repository = repository(fetcher = fetcher, memo = memo)

        assertTrue(runBlocking { repository.check(manual = false) } is CheckOutcome.Unavailable)
        assertEquals(1, fetcher.notModifiedCalls)
    }

    @Test
    fun `a version the user said 後で to is still available but marked skipped`() {
        val memo = InMemoryUpdateMemo(skipped = "0.4.5-phase2")
        val repository = repository(fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2")), memo = memo)
        val available = runBlocking { repository.check(manual = true) } as CheckOutcome.Available
        assertTrue(available.skipped)
    }

    @Test
    fun `a newer release than the skipped one is offered again`() {
        val memo = InMemoryUpdateMemo(skipped = "0.4.5-phase2")
        val repository = repository(fetcher = FakeReleaseFetcher(releasePayload("v0.4.6-phase2")), memo = memo)
        val available = runBlocking { repository.check(manual = false) } as CheckOutcome.Available
        assertEquals("0.4.6-phase2", available.info.versionName)
        assertFalse(available.skipped)
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

    // ── 0.4.7: the APK of the version that is now running is deleted ─────────

    @Test
    fun `the cleanup deletes the running version's apk, older ones and partial downloads`() {
        val repository = repository()
        val directory = File(temporary.root, "updates").apply { mkdirs() }
        val running = apk(directory, "pianosheet-0.4.4-phase2.apk")
        val older = apk(directory, "pianosheet-0.4.3-phase2.apk")
        val partial = apk(directory, "pianosheet-0.4.5-phase2.apk.part")

        val removed = runBlocking { repository.pruneStaleApks() }

        assertFalse(running.exists())
        assertFalse(older.exists())
        assertFalse(partial.exists())
        assertEquals(3, removed.size)
    }

    @Test
    fun `the cleanup keeps an apk of a newer version the user has downloaded`() {
        val repository = repository()
        val directory = File(temporary.root, "updates").apply { mkdirs() }
        val pending = apk(directory, "pianosheet-0.4.5-phase2.apk")

        runBlocking { repository.pruneStaleApks() }

        assertTrue(pending.exists())
    }

    @Test
    fun `the cleanup deletes files it cannot recognise but leaves a fresh one alone`() {
        val repository = repository()
        val directory = File(temporary.root, "updates").apply { mkdirs() }
        val junk = apk(directory, "download.apk")
        val kept = File(directory, "readme.txt").apply { writeText("x") }

        runBlocking { repository.pruneStaleApks() }

        assertFalse(junk.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun `a download deletes what the previous version left behind first`() {
        val downloader = FakeApkDownloader(apkBytes = 3_000_000L)
        val repository = repository(downloader = downloader)
        val directory = File(temporary.root, "updates").apply { mkdirs() }
        val previous = apk(directory, "pianosheet-0.4.4-phase2.apk")
        val partial = apk(directory, "pianosheet-0.4.5-phase2.apk.part")
        val info = availableInfo(repository)

        val file = runBlocking { repository.download(info) { _, _ -> } }.getOrThrow()

        assertFalse(previous.exists())
        assertFalse(partial.exists())
        assertTrue(file.exists())
        assertEquals(3_000_000L, file.length())
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun repository(
        fetcher: FakeReleaseFetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2")),
        downloader: FakeApkDownloader = FakeApkDownloader(),
        memo: UpdateMemo = InMemoryUpdateMemo(),
        now: Long = 10_000L,
    ) = repository(fetcher = fetcher, downloader = downloader, memo = memo, now = { now })

    private fun repository(
        fetcher: FakeReleaseFetcher,
        downloader: FakeApkDownloader = FakeApkDownloader(),
        memo: UpdateMemo = InMemoryUpdateMemo(),
        now: () -> Long,
    ) = UpdateRepository(
        fetcher = fetcher,
        downloader = downloader,
        memo = memo,
        apkDirectory = File(temporary.root, "updates"),
        currentVersionName = "0.4.4-phase2",
        currentVersionCode = 11,
        minCheckIntervalMs = UpdateRepository.DEFAULT_AUTO_CHECK_DEBOUNCE_MS,
        clock = now,
    )

    /** A file of the size an APK download has, only its header matters to the policy. */
    private fun apk(directory: File, name: String): File =
        File(directory, name).apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }

    private fun availableInfo(repository: UpdateRepository) =
        (runBlocking { repository.check(manual = true) } as CheckOutcome.Available).info
}
