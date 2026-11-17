package com.yuushilabs.pianosheet.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled viewer has to be able to fill whatever height the WebView is given.
 *
 * 0.4.1 shipped `#paper { min-height: 120px }` and a WebView without layout
 * params, so the page's viewport was exactly those 120 px: the sheet showed the
 * top third of the first staff and the rest of the panel was empty.  The CSS is
 * the half of that bug a JVM test can see (the other half is the MATCH_PARENT in
 * `ScoreWebView`; `work/viewer-check` renders the page at phone size to check the
 * result).
 */
class ScoreViewerAssetsTest {

    private fun asset(name: String): String {
        val dir = System.getProperty("pianosheet.assetsDir") ?: "src/main/assets"
        val file = File(dir, "score/$name")
        require(file.isFile) { "missing ${file.absolutePath}" }
        return file.readText()
    }

    @Test
    fun `the sheet fills the viewer instead of a fixed stub`() {
        val css = asset("viewer.css")
        assertTrue("viewer.css must not pin #paper to a fixed short height", !css.contains("min-height: 120px"))
        assertTrue("the sheet fills the scroller", css.contains("min-height: 100%"))
    }

    @Test
    fun `the last system can scroll clear of the zoom control`() {
        val css = asset("viewer.css")
        val padding = Regex("""padding:\s*8px\s+8px\s+(\d+)px\s+8px""").find(css)
            ?: error("scroller padding rule not found")
        val bottom = padding.groupValues[1].toInt()
        assertTrue("bottom padding $bottom px must clear the zoom control", bottom >= 48)
    }

    @Test
    fun `the score is re-engraved when the web view is resized`() {
        val js = asset("viewer.js")
        assertTrue("OSMD must follow the container width", js.contains("autoResize: true"))
        assertTrue(
            "a resize has to reach the renderer, not only the play-head",
            js.contains("addEventListener('resize'"),
        )
    }
}
