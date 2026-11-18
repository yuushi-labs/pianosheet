package com.yuushilabs.pianosheet.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.5: only a genuinely newer, promoted release with a real APK attached may
 * reach the user's phone — the update runs in their name, so a same-version
 * release, a draft or a missing file must end as "nothing to do".
 */
class UpdateDecisionTest {

    private val apkUrl = "https://github.com/yuushi-labs/pianosheet/releases/download/v0.4.5-phase2/pianosheet-0.4.5-phase2-debug.apk"

    private fun release(
        tag: String?,
        apk: String? = "pianosheet-0.4.5-phase2-debug.apk",
        body: String? = "### 0.4.5-phase2\n- アプリ内アップデート\n- 設定にバージョン表示",
        draft: Boolean = false,
        prerelease: Boolean = false,
        size: Long = 92_000_000L,
    ) = GithubRelease(
        tagName = tag,
        name = "PianoSheet $tag",
        body = body,
        prerelease = prerelease,
        draft = draft,
        htmlUrl = "https://github.com/yuushi-labs/pianosheet/releases/tag/$tag",
        assets = if (apk == null) {
            emptyList()
        } else {
            listOf(ReleaseAsset(apk, apkUrl, size, "application/vnd.android.package-archive"))
        },
    )

    @Test
    fun `a newer release is offered with its APK`() {
        val info = UpdateDecision.decide("0.4.4-phase2", 11, release("v0.4.5-phase2"))!!
        assertEquals("0.4.5-phase2", info.versionName)
        assertEquals(apkUrl, info.apkUrl)
        assertEquals("pianosheet-0.4.5-phase2-debug.apk", info.apkFileName)
        assertEquals(92_000_000L, info.apkSizeBytes)
        assertTrue(info.notes.contains("アプリ内アップデート"))
    }

    @Test
    fun `the same version is not an update`() {
        assertNull(UpdateDecision.decide("0.4.5-phase2", 12, release("v0.4.5-phase2")))
        assertNull(UpdateDecision.decide("0.4.5-phase2", 12, release("0.4.5-phase2")))
    }

    @Test
    fun `an older release is not an update`() {
        assertNull(UpdateDecision.decide("0.4.5-phase2", 12, release("v0.4.4-phase2")))
    }

    @Test
    fun `a draft or a pre-release is never offered`() {
        assertNull(UpdateDecision.decide("0.4.4-phase2", 11, release("v0.4.5-phase2", draft = true)))
        assertNull(
            UpdateDecision.decide("0.4.4-phase2", 11, release("v0.4.5-phase2", prerelease = true)),
        )
    }

    @Test
    fun `a release without an APK attached is not an update`() {
        assertNull(UpdateDecision.decide("0.4.4-phase2", 11, release("v0.4.5-phase2", apk = null)))
    }

    @Test
    fun `a suspiciously small apk is not trusted`() {
        assertNull(
            UpdateDecision.decide("0.4.4-phase2", 11, release("v0.4.5-phase2", size = 1_024L)),
        )
    }

    @Test
    fun `a tag that is not a version falls back to the declared versionCode`() {
        val info = UpdateDecision.decide(
            "0.4.4-phase2",
            11,
            release("build-2026-11-18", body = "versionCode: 12\n- アプリ内アップデート"),
        )!!
        assertEquals(12, info.versionCode)
    }

    @Test
    fun `a declared versionCode that is not newer is not an update`() {
        assertNull(
            UpdateDecision.decide(
                "0.4.4-phase2",
                11,
                release("build-2026-11-18", body = "versionCode = 11"),
            ),
        )
    }

    @Test
    fun `the apk file name can carry the versionCode`() {
        val info = UpdateDecision.decide(
            "0.4.4-phase2",
            11,
            release("build-2026-11-18", apk = "pianosheet-v12-debug.apk"),
        )!!
        assertEquals(12, info.versionCode)
    }

    @Test
    fun `versionCode is read in the three spellings release notes use`() {
        assertEquals(12, UpdateDecision.declaredVersionCode("versionCode: 12"))
        assertEquals(12, UpdateDecision.declaredVersionCode("versionCode=12"))
        assertEquals(12, UpdateDecision.declaredVersionCode("versionCode 12"))
        assertEquals(12, UpdateDecision.declaredVersionCode("VersionCode: 12"))
        assertNull(UpdateDecision.declaredVersionCode("バージョン 0.4.5"))
        assertNull(UpdateDecision.declaredVersionCode(null))
    }

    @Test
    fun `the notes drop markdown and the version heading`() {
        val summary = UpdateDecision.summaryOf(
            """
            ## v0.4.5-phase2
            - アプリ内アップデートを追加
            - **設定**にバージョンを表示
            - [リリースノート](https://example.com)を見る
            """.trimIndent(),
        )
        assertEquals(
            "アプリ内アップデートを追加\n設定にバージョンを表示\nリリースノートを見る",
            summary,
        )
    }

    @Test
    fun `the notes are bounded`() {
        val body = (1..40).joinToString("\n") { "- 変更 $it" }
        val summary = UpdateDecision.summaryOf(body)
        assertEquals(6, summary.lines().size)
        assertTrue(summary.length <= 401)
    }

    @Test
    fun `the notes do not show the machine readable versionCode`() {
        val summary = UpdateDecision.summaryOf(
            """
            PianoSheet 0.4.5-phase2（versionCode 12）
            - アプリ内アップデートを追加
            versionCode: 12
            """.trimIndent(),
        )
        assertEquals("PianoSheet 0.4.5-phase2（versionCode 12）\nアプリ内アップデートを追加", summary)
    }

    @Test
    fun `an empty body gives empty notes`() {
        assertEquals("", UpdateDecision.summaryOf(null))
        assertEquals("", UpdateDecision.summaryOf("\n\n"))
    }
}
