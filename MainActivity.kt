package com.example.tradingbuddy

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var statusInsetCss = 0
    private var navigationInsetCss = 0
    private var pageLoaded = false
    private var statusBarOverlay: View? = null
    private var lastKeyboardHeight = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isScrollbarFadingEnabled = true
        webView.setBackgroundColor("#0d0f14".toColorInt())

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowUniversalAccessFromFileURLs = true
        }

        // Keyboard height detection via global layout listener
        val rootView = window.decorView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleRect = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleRect)
            val density = resources.displayMetrics.density
            val screenHeight = rootView.height
            val keyboardPx = maxOf(0, screenHeight - visibleRect.bottom)
            val keyboardCss = (keyboardPx / density).toInt()
            if (keyboardCss != lastKeyboardHeight) {
                lastKeyboardHeight = keyboardCss
                notifyKeyboardHeight(keyboardCss)
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun fetchRoadmap() {
                Thread {
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        conn = java.net.URL(
                            "https://gist.githubusercontent.com/Ratzfratz/4d6b4d850adf9883970453c2092699de/raw/roadmap.json"
                        ).openConnection() as java.net.HttpURLConnection

                        conn.instanceFollowRedirects = true
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 12000
                        conn.readTimeout = 12000
                        conn.useCaches = false
                        conn.setRequestProperty("Accept", "application/json")
                        conn.setRequestProperty("Cache-Control", "no-cache")
                        conn.setRequestProperty("User-Agent", "TradingBuddy-Android")

                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                        if (code !in 200..299) {
                            throw java.io.IOException("GitHub HTTP $code${if (text.isNotBlank()) ": ${text.take(120)}" else ""}")
                        }

                        postRoadmapData(text, null)
                    } catch (e: Exception) {
                        postRoadmapData(null, e.message ?: e.javaClass.simpleName ?: "Netzwerkfehler")
                    } finally {
                        conn?.disconnect()
                    }
                }.start()
            }
            @JavascriptInterface
            fun onRoadmapOpen() {
                runOnUiThread {
                    statusBarOverlay?.let { v ->
                        v.animate().cancel()
                        val anim = android.animation.ValueAnimator.ofArgb(
                            android.graphics.Color.rgb(13, 15, 20),
                            android.graphics.Color.argb(115, 0, 0, 0)
                        )
                        anim.duration = 300
                        anim.addUpdateListener { v.setBackgroundColor(it.animatedValue as Int) }
                        anim.start()
                    }
                }
            }
            @JavascriptInterface
            fun onRoadmapClose() {
                runOnUiThread {
                    statusBarOverlay?.let { v ->
                        val anim = android.animation.ValueAnimator.ofArgb(
                            android.graphics.Color.argb(115, 0, 0, 0),
                            android.graphics.Color.rgb(13, 15, 20)
                        )
                        anim.duration = 300
                        anim.addUpdateListener { v.setBackgroundColor(it.animatedValue as Int) }
                        anim.start()
                    }
                }
            }
        }, "TBNative")

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            statusInsetCss = (statusBars.top / density).toInt()
            navigationInsetCss = (navigationBars.bottom / density).toInt()
            addStatusBarOverlay(statusBars.top)
            applyInsetsToWebPage()
            insets
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                applyInsetsToWebPage()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file://")) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true }
                catch (_: Exception) { false }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(typeof handleBackPressed === 'function') ? handleBackPressed() : false"
                ) { result ->
                    if (result != "true") {
                        if (webView.canGoBack()) webView.goBack()
                        else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                    }
                }
            }
        })

        webView.loadUrl("file:///android_asset/app.html")
        ViewCompat.requestApplyInsets(webView)
    }

    private fun postRoadmapData(jsonText: String?, errorMessage: String?) {
        val dataArg = jsonText?.let { JSONObject.quote(it) } ?: "null"
        val errorArg = errorMessage?.let { JSONObject.quote(it) } ?: "null"
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onRoadmapData==='function')onRoadmapData($dataArg,$errorArg);",
                null
            )
        }
    }

    private fun notifyKeyboardHeight(keyboardCss: Int) {
        if (!pageLoaded) return
        runOnUiThread {
            webView.evaluateJavascript("if(typeof onKeyboardHeight==='function')onKeyboardHeight($keyboardCss);", null)
        }
    }

    private fun addStatusBarOverlay(heightPx: Int) {
        if (heightPx <= 0) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        statusBarOverlay?.let { root.removeView(it) }
        val overlay = View(this)
        overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, heightPx)
        overlay.setBackgroundColor(Color.rgb(13, 15, 20))
        root.addView(overlay)
        statusBarOverlay = overlay
    }

    private fun applyInsetsToWebPage() {
        if (!pageLoaded) return
        val script = """
            document.documentElement.style.setProperty('--status-inset','${statusInsetCss}px');
            document.documentElement.style.setProperty('--nav-inset','${navigationInsetCss}px');
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
}
