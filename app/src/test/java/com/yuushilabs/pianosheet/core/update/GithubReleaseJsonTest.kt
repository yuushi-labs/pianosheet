package com.yuushilabs.pianosheet.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields of `GET /repos/yuushi-labs/pianosheet/releases/latest` the app uses. */
class GithubReleaseJsonTest {

    private val payload = """
        {
          "url": "https://api.github.com/repos/yuushi-labs/pianosheet/releases/1",
          "html_url": "https://github.com/yuushi-labs/pianosheet/releases/tag/v0.4.5-phase2",
          "tag_name": "v0.4.5-phase2",
          "name": "PianoSheet 0.4.5-phase2",
          "draft": false,
          "prerelease": false,
          "body": "### 更新\n- アプリ内アップデート\n",
          "assets": [
            {
              "name": "pianosheet-0.4.5-phase2-debug.apk",
              "content_type": "application/vnd.android.package-archive",
              "size": 92000000,
              "browser_download_url": "https://github.com/yuushi-labs/pianosheet/releases/download/v0.4.5-phase2/pianosheet-0.4.5-phase2-debug.apk"
            },
            {
              "name": "app-debug.apk.sha256",
              "content_type": "text/plain",
              "size": 90,
              "browser_download_url": "https://github.com/yuushi-labs/pianosheet/releases/download/v0.4.5-phase2/app-debug.apk.sha256"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the release fields are read`() {
        val release = GithubReleaseJson.parse(payload)!!
        assertEquals("v0.4.5-phase2", release.tagName)
        assertEquals("PianoSheet 0.4.5-phase2", release.name)
        assertEquals(false, release.draft)
        assertEquals(false, release.prerelease)
        assertTrue(release.body!!.contains("アプリ内アップデート"))
        assertEquals(
            "https://github.com/yuushi-labs/pianosheet/releases/tag/v0.4.5-phase2",
            release.htmlUrl,
        )
        assertEquals(2, release.assets.size)
        assertEquals(92_000_000L, release.assets.first().sizeBytes)
    }

    @Test
    fun `an asset without a download url is dropped`() {
        val release = GithubReleaseJson.parse(
            """{"tag_name":"v0.4.6","assets":[{"name":"x.apk"}]}""",
        )!!
        assertEquals(0, release.assets.size)
    }

    @Test
    fun `a pre-release is parsed and flagged`() {
        val release = GithubReleaseJson.parse(
            """{"tag_name":"v0.6.0-rc1","prerelease":true,"assets":[]}""",
        )!!
        assertTrue(release.prerelease)
        assertFalse(release.draft)
    }

    @Test
    fun `a repository without releases answers with an error object`() {
        assertNull(GithubReleaseJson.parse("""{"message":"Not Found","documentation_url":"https://docs.github.com"}"""))
    }

    @Test
    fun `an html error page is not a release`() {
        assertNull(GithubReleaseJson.parse("<html><body>502 Bad Gateway</body></html>"))
        assertNull(GithubReleaseJson.parse(null))
    }

    @Test
    fun `a document with neither a tag nor assets is not a release`() {
        assertNull(GithubReleaseJson.parse("""{"name":"build 12"}"""))
    }
}
