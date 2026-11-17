package com.yuushilabs.pianosheet.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.Locale

/** Where the play-head should stand: a measure and a beat inside it. */
data class ScorePlayhead(
    val measureIndex: Int,
    val beatInMeasure: Double,
    val beatsInMeasure: Double,
    /** Scroll the page so the play-head stays visible (true while playing). */
    val follow: Boolean,
)

/**
 * The score view (spec §3.6): the MusicXML the app generates, engraved by
 * OpenSheetMusicDisplay inside a WebView.
 *
 * The renderer and its fonts are bundled in `assets/score`, the WebView has
 * network loads blocked, and the only thing it can do is draw and move the
 * play-head — so the sheet looks the same offline, and the app never depends on
 * a CDN for a page a child is reading music from.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScoreWebView(
    musicXml: String,
    darkTheme: Boolean,
    zoom: Float,
    playhead: ScorePlayhead?,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
) {
    val controller = remember { ViewerController() }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                // Everything the viewer needs is inside the APK; forbidding
                // network loads makes that a guarantee rather than a promise.
                settings.blockNetworkLoads = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                addJavascriptInterface(ViewerBridge(onError), "PianoSheetBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        controller.onPageReady(view)
                    }
                }
                loadUrl("file:///android_asset/score/index.html")
                controller.attach(this)
            }
        },
        update = { webView ->
            controller.push(webView, musicXml, darkTheme, zoom, playhead)
        },
    )
}

// --------------------------------------------------------------------- state

private data class Desired(
    val xml: String,
    val dark: Boolean,
    val zoom: Float,
    val playhead: ScorePlayhead?,
)

/**
 * Keeps what the page has been told, so a recomposition only sends the changes.
 * The WebView can be recreated by the system (a rotation, or the activity being
 * recreated), and the page reports when it is ready again.
 */
private class ViewerController {

    private var webView: WebView? = null
    private var pageReady = false
    private var desired: Desired? = null
    private var appliedXml: String? = null
    private var appliedDark: Boolean? = null
    private var appliedZoom: Float? = null
    private var appliedHead: String? = null

    fun attach(view: WebView) {
        webView = view
    }

    fun onPageReady(view: WebView) {
        webView = view
        pageReady = true
        // The page starts empty: force the whole state onto it once.
        appliedXml = null
        appliedDark = null
        appliedZoom = null
        appliedHead = null
        flush(view)
    }

    fun release() {
        webView = null
        pageReady = false
    }

    fun push(view: WebView, xml: String, dark: Boolean, zoom: Float, playhead: ScorePlayhead?) {
        webView = view
        desired = Desired(xml, dark, zoom, playhead)
        if (!pageReady) return
        flush(view)
    }

    private fun flush(view: WebView) {
        val target = desired ?: return
        if (target.xml != appliedXml) {
            appliedXml = target.xml
            appliedDark = target.dark
            appliedZoom = target.zoom
            appliedHead = null
            view.evaluateJavascript(
                "window.PianoSheet&&PianoSheet.loadScore(" +
                    "${JSONObject.quote(target.xml)}, {dark:${target.dark}, zoom:${number(target.zoom)}})",
                null,
            )
        }
        if (appliedDark != target.dark) {
            appliedDark = target.dark
            view.evaluateJavascript("window.PianoSheet&&PianoSheet.setTheme(${target.dark})", null)
        }
        if (appliedZoom != target.zoom) {
            appliedZoom = target.zoom
            view.evaluateJavascript("window.PianoSheet&&PianoSheet.setZoom(${number(target.zoom)})", null)
        }
        val playhead = target.playhead
        val key = playhead?.let {
            "${it.measureIndex}:${(it.beatInMeasure * 20).toInt()}:${it.follow}"
        }
        if (key != appliedHead) {
            appliedHead = key
            if (playhead == null) {
                view.evaluateJavascript("window.PianoSheet&&PianoSheet.clearPosition()", null)
            } else {
                view.evaluateJavascript(
                    "window.PianoSheet&&PianoSheet.setPosition(" +
                        "${playhead.measureIndex}, ${number(playhead.beatInMeasure)}, " +
                        "${number(playhead.beatsInMeasure)}, ${playhead.follow})",
                    null,
                )
            }
        }
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun number(value: Float): String = String.format(Locale.US, "%.3f", value)
}

/** What the page tells the app: rendered, ready, or an error worth showing. */
private class ViewerBridge(
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun onEvent(json: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val message = JSONObject(json)
                if (message.optBoolean("ok", true)) return@runCatching
                val error = message.optString("error").ifBlank { "楽譜を表示できませんでした" }
                onError(error)
            }
        }
    }
}
