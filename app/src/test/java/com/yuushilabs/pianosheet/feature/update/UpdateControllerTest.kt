package com.yuushilabs.pianosheet.feature.update

import com.yuushilabs.pianosheet.core.update.ApkDownloader
import com.yuushilabs.pianosheet.data.update.FakeApkDownloader
import com.yuushilabs.pianosheet.data.update.FakeReleaseFetcher
import com.yuushilabs.pianosheet.data.update.InMemoryUpdateMemo
import com.yuushilabs.pianosheet.data.update.NoOpInstaller
import com.yuushilabs.pianosheet.data.update.UpdateRepository
import com.yuushilabs.pianosheet.data.update.releasePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 0.4.5: what the home banner and the settings section see.  The controller is
 * the state machine between the check and the install; a fake release and a
 * fake transfer drive it, and the coroutine scope runs unconfined so each step
 * is observable as the state it leaves behind.
 */
class UpdateControllerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `an automatic check offers a newer release`() {
        val controller = controller()
        controller.autoCheck()
        val available = controller.state.value as UpdateUiState.Available
        assertEquals("0.4.5-phase2", available.info.versionName)
        assertFalse(available.skipped)
    }

    @Test
    fun `the running build being newest is reported as up to date`() {
        val controller = controller(fetcher = FakeReleaseFetcher(releasePayload("v0.4.4-phase2")))
        controller.checkNow()
        assertEquals(UpdateUiState.Latest, controller.state.value)
    }

    @Test
    fun `an automatic check that fails stays quiet`() {
        val controller = controller(fetcher = FakeReleaseFetcher("<html>502</html>"))
        controller.autoCheck()
        val failed = controller.state.value as UpdateUiState.Failed
        assertFalse(failed.manual)
    }

    @Test
    fun `a manual check that fails is shown`() {
        val controller = controller(fetcher = FakeReleaseFetcher("<html>502</html>"))
        controller.checkNow()
        assertTrue((controller.state.value as UpdateUiState.Failed).manual)
    }

    @Test
    fun `a dismissed failure goes back to idle`() {
        val controller = controller(fetcher = FakeReleaseFetcher("<html>502</html>"))
        controller.checkNow()
        controller.dismissFailure()
        assertEquals(UpdateUiState.Idle, controller.state.value)
    }

    @Test
    fun `the launch check asks GitHub only once per process`() {
        val fetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2"))
        val controller = controller(fetcher = fetcher)
        controller.autoCheck()
        controller.autoCheck()
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `後で marks the version skipped and remembers it`() {
        val memo = InMemoryUpdateMemo()
        val controller = controller(memo = memo)
        controller.autoCheck()
        controller.skip()
        assertTrue((controller.state.value as UpdateUiState.Available).skipped)
        assertEquals("0.4.5-phase2", memo.skipped)
    }

    @Test
    fun `今すぐ更新 downloads the apk and becomes ready`() {
        val downloader = FakeApkDownloader(apkBytes = 3_000_000L)
        val controller = controller(downloader = downloader)
        controller.autoCheck()
        controller.downloadNow()

        val ready = controller.state.value as UpdateUiState.Ready
        assertTrue(ready.apk.exists())
        assertEquals(3_000_000L, ready.apk.length())
        assertEquals(ready.apk.absolutePath, controller.apkToInstall()?.absolutePath)
    }

    @Test
    fun `a download that fails is reported`() {
        val controller = controller(downloader = FakeApkDownloader(failure = java.io.IOException("x")))
        controller.autoCheck()
        controller.downloadNow()
        assertTrue((controller.state.value as UpdateUiState.Failed).manual)
    }

    @Test
    fun `キャンセル stops the transfer and keeps the update on offer`() = runBlocking {
        val controller = controller(downloader = NeverFinishingDownloader())
        controller.autoCheck()
        controller.downloadNow()
        assertTrue(controller.state.value is UpdateUiState.Downloading)

        controller.cancelDownload()

        val available = controller.state.value as UpdateUiState.Available
        assertFalse(available.skipped)
    }

    @Test
    fun `opening the installer stops the banner from asking again`() {
        val memo = InMemoryUpdateMemo()
        val controller = controller(memo = memo)
        controller.autoCheck()
        controller.downloadNow()
        controller.installStarted()

        assertTrue((controller.state.value as UpdateUiState.Available).skipped)
        assertEquals("0.4.5-phase2", memo.skipped)
    }

    @Test
    fun `downloading without an offer does nothing`() {
        val controller = controller()
        controller.downloadNow()
        assertEquals(UpdateUiState.Idle, controller.state.value)
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun controller(
        fetcher: FakeReleaseFetcher = FakeReleaseFetcher(releasePayload("v0.4.5-phase2")),
        downloader: ApkDownloader = FakeApkDownloader(),
        memo: InMemoryUpdateMemo = InMemoryUpdateMemo(),
    ): UpdateController = UpdateController(
        repository = UpdateRepository(
            fetcher = fetcher,
            downloader = downloader,
            memo = memo,
            apkDirectory = File(temporary.root, "updates"),
            currentVersionName = "0.4.4-phase2",
            currentVersionCode = 11,
            clock = { 10_000L },
        ),
        installer = NoOpInstaller,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private class NeverFinishingDownloader : ApkDownloader {
        override suspend fun download(
            url: String,
            target: File,
            expectedBytes: Long,
            onProgress: (Long, Long) -> Unit,
        ): File {
            onProgress(1_000L, expectedBytes)
            awaitCancellation()
        }
    }
}
