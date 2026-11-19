package com.stemplayer.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray

/**
 * Renders a MusicXML score with Verovio (bundled in assets/score) inside a WebView.
 * The page highlights the measure given by highlight(i) and reports taps on measures.
 */
class ScoreWebView(context: Context, private val xml: String, measureNumbers: List<String>, zoom: Int) {

    var onMeasureTap: (Int) -> Unit = {}

    private val numbersJson = JSONArray(measureNumbers).toString()
    @Volatile private var zoom = zoom
    private var lastHighlight = -1
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The page never navigates anywhere else.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        addJavascriptInterface(Bridge(), "Android")
        loadUrl("https://appassets.androidplatform.net/assets/score/index.html")
    }

    fun highlight(measure: Int) {
        if (measure == lastHighlight) return
        lastHighlight = measure
        view.evaluateJavascript("window.highlight && highlight($measure)", null)
    }

    fun setZoom(z: Int) {
        if (z == zoom) return
        zoom = z
        view.evaluateJavascript("window.setZoom && setZoom($z)", null)
    }

    fun destroy() {
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface fun getScore(): String = xml
        @JavascriptInterface fun getMeasureNumbers(): String = numbersJson
        @JavascriptInterface fun getZoom(): Int = zoom
        @JavascriptInterface fun onMeasureTap(index: Int) { main.post { onMeasureTap.invoke(index) } }
        @JavascriptInterface fun onRendered() {
            // Re-send the current measure in case it arrived before the score was ready.
            main.post {
                val m = lastHighlight
                lastHighlight = -2
                if (m >= 0) highlight(m)
            }
        }
    }
}

@Composable
fun ScorePanel(
    web: ScoreWebView,
    zoom: Int,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AndroidView(
            factory = {
                (web.view.parent as? ViewGroup)?.removeView(web.view)
                web.view
            },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = { onZoom((zoom - 5).coerceAtLeast(MIN_ZOOM)) }) {
                Icon(Icons.Default.ZoomOut, "Smaller")
            }
            SmallFloatingActionButton(onClick = { onZoom((zoom + 5).coerceAtMost(MAX_ZOOM)) }) {
                Icon(Icons.Default.ZoomIn, "Bigger")
            }
        }
    }
}

const val MIN_ZOOM = 15
const val MAX_ZOOM = 80
