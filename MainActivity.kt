package com.example.tradingbuddy

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class MainActivity : AppCompatActivity() {

    private val localAppOrigin = "https://localhost"
    private val localAppUrl = "$localAppOrigin/app.html?v=pixel-restore-20260702-1"
    private lateinit var webView: WebView
    private var statusInsetCss = 0
    private var navigationInsetCss = 0
    private var pageLoaded = false
    private var lastKeyboardHeight = 0
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val files = if (result.resultCode == RESULT_OK) {
            val uris = linkedSetOf<Uri>()
            result.data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) uris.add(clips.getItemAt(index).uri)
            }
            result.data?.data?.let(uris::add)
            uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        } else {
            null
        }
        filePathCallback?.onReceiveValue(files)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // WebView ausdrücklich hinter Status- und Navigationsleiste rendern.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor("#0d0f14".toColorInt())

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.clearCache(true)

        // Cookies von cloud.appwrite.io erlauben (nötig für Appwrite-Auth von file://)
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // (Keyboard-Erkennung erfolgt im WindowInsets-Listener weiter unten,
        //  zusammen mit Status-/Navigationsleisten-Insets.)

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
            fun fetchDerivativeDetails(identifier: String) {
                Thread {
                    val clean = identifier.trim().uppercase().replace("\\s+".toRegex(), "")
                    if (clean.length < 6) {
                        postDerivativeDetails(null, "Bitte WKN oder ISIN eingeben.")
                        return@Thread
                    }
                    try {
                        val details = fetchOnvistaDerivativeDetails(clean)
                            ?: throw java.io.IOException("Keine Produktdaten gefunden")
                        val payload = JSONObject().put(clean, details).toString()
                        postDerivativeDetails(payload, null)
                    } catch (e: Exception) {
                        postDerivativeDetails(null, e.message ?: "Keine Produktdaten gefunden")
                    }
                }.start()
            }
            @JavascriptInterface
            fun setLightTheme(isLight: Boolean) {
                runOnUiThread {
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = isLight
                    insetsController.isAppearanceLightNavigationBars = isLight
                    webView.setBackgroundColor(
                        if (isLight) Color.rgb(246, 248, 252)
                        else Color.rgb(13, 15, 20)
                    )
                }
            }

            // ── Verschlüsselungsschlüssel sicher in Android Keystore speichern ──
            private fun getEncPrefs(): android.content.SharedPreferences {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                return EncryptedSharedPreferences.create(
                    "tb_enc_key_store",
                    masterKeyAlias,
                    this@MainActivity,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }

            @JavascriptInterface
            fun storeEncKey(base64Key: String): Boolean {
                return try {
                    val editor = getEncPrefs().edit()
                    editor.putString("enc_key", base64Key)
                    editor.commit()
                } catch (e: Exception) { false }
            }

            @JavascriptInterface
            fun retrieveEncKey(): String {
                return try {
                    getEncPrefs().getString("enc_key", "") ?: ""
                } catch (e: Exception) { "" }
            }

            @JavascriptInterface
            fun clearEncKey() {
                try {
                    val editor = getEncPrefs().edit()
                    editor.remove("enc_key")
                    editor.commit()
                } catch (_: Exception) {}
            }

            @JavascriptInterface
            fun persistCloudSession() {
                try {
                    android.webkit.CookieManager.getInstance().flush()
                } catch (_: Exception) {}
            }

            @JavascriptInterface
            fun downloadAppwriteFile(url: String, jwt: String, project: String): String {
                var conn: java.net.HttpURLConnection? = null
                return try {
                    conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 20000
                    conn.readTimeout = 30000
                    conn.useCaches = false
                    conn.setRequestProperty("X-Appwrite-Project", project)
                    if (jwt.isNotBlank()) conn.setRequestProperty("X-Appwrite-JWT", jwt)
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    conn.setRequestProperty("User-Agent", "TradingBuddy-Android")

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    if (code !in 200..299) {
                        val text = bytes.toString(Charsets.UTF_8).take(180)
                        throw java.io.IOException("HTTP $code${if (text.isNotBlank()) ": $text" else ""}")
                    }
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    JSONObject().put("ok", true).put("data", b64).toString()
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: e.javaClass.simpleName ?: "Download fehlgeschlagen")
                        .toString()
                } finally {
                    conn?.disconnect()
                }
            }

            @JavascriptInterface
            fun downloadAppwriteStorageFile(endpoint: String, bucketId: String, fileId: String, jwt: String, project: String): String {
                val safeEndpoint = endpoint.trimEnd('/')
                val encodedBucket = java.net.URLEncoder.encode(bucketId, "UTF-8")
                val encodedFile = java.net.URLEncoder.encode(fileId, "UTF-8")
                val encodedProject = java.net.URLEncoder.encode(project, "UTF-8")
                val directUrl = "$safeEndpoint/storage/buckets/$encodedBucket/files/$encodedFile/download?project=$encodedProject"
                return downloadAppwriteFile(directUrl, jwt, project)
            }

            @JavascriptInterface
            fun gzipBase64(base64Json: String): String {
                return try {
                    val input = android.util.Base64.decode(base64Json, android.util.Base64.NO_WRAP)
                    val out = java.io.ByteArrayOutputStream()
                    java.util.zip.GZIPOutputStream(out).use { gzip ->
                        gzip.write(input)
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("data", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
                        .toString()
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: e.javaClass.simpleName ?: "Gzip fehlgeschlagen")
                        .toString()
                }
            }

            @JavascriptInterface
            fun gunzipBase64(base64Gzip: String): String {
                return try {
                    val input = android.util.Base64.decode(base64Gzip, android.util.Base64.NO_WRAP)
                    val text = java.util.zip.GZIPInputStream(input.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject()
                        .put("ok", true)
                        .put("data", android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                        .toString()
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: e.javaClass.simpleName ?: "Gunzip fehlgeschlagen")
                        .toString()
                }
            }
        }, "TBNative")

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            statusInsetCss = (statusBars.top / density).toInt()
            navigationInsetCss = (navigationBars.bottom / density).toInt()
            applyInsetsToWebPage()

            // Tastaturhöhe per IME-Inset (zuverlässig bei Edge-to-Edge).
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val keyboardPx = maxOf(0, imeBottom - navigationBars.bottom)
            val keyboardCss = (keyboardPx / density).toInt()
            if (keyboardCss != lastKeyboardHeight) {
                lastKeyboardHeight = keyboardCss
                notifyKeyboardHeight(keyboardCss)
            }
            insets
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                applyInsetsToWebPage()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.scheme != "https" || url.host != "localhost") return null
                val assetPath = when (val path = url.path.orEmpty().trimStart('/')) {
                    "", "/" -> "app.html"
                    else -> path
                }
                return try {
                    val mime = when {
                        assetPath.endsWith(".html", true) -> "text/html"
                        assetPath.endsWith(".css", true) -> "text/css"
                        assetPath.endsWith(".js", true) -> "application/javascript"
                        assetPath.endsWith(".json", true) -> "application/json"
                        assetPath.endsWith(".png", true) -> "image/png"
                        assetPath.endsWith(".jpg", true) || assetPath.endsWith(".jpeg", true) -> "image/jpeg"
                        assetPath.endsWith(".svg", true) -> "image/svg+xml"
                        assetPath.endsWith(".webp", true) -> "image/webp"
                        else -> "application/octet-stream"
                    }
                    val headers = mapOf(
                        "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
                        "Pragma" to "no-cache",
                        "Expires" to "0"
                    )
                    WebResourceResponse(mime, "UTF-8", 200, "OK", headers, assets.open(assetPath))
                } catch (_: Exception) {
                    null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith(localAppOrigin)) return false
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

        webView.loadUrl(localAppUrl)
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

    private fun postDerivativeDetails(jsonText: String?, errorMessage: String?) {
        val dataArg = jsonText?.let { JSONObject.quote(it) } ?: "null"
        val errorArg = errorMessage?.let { JSONObject.quote(it) } ?: "null"
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onDerivativeDetails==='function')onDerivativeDetails($dataArg,$errorArg);",
                null
            )
        }
    }

    private fun fetchOnvistaDerivativeDetails(identifier: String): JSONObject? {
        val direct = onvistaSnapshotForIdentifier(identifier)
        if (direct != null) return derivativeDetailsFromSnapshot(direct, identifier)

        val instrument = searchOnvistaInstrument(identifier) ?: return null
        if (!isDerivativeInstrument(instrument)) {
            return instrumentDetailsFromSearchHit(instrument, identifier)
        }

        val isin = instrument.optString("isin").takeIf { it.isNotBlank() }
        val wkn = instrument.optString("wkn").takeIf { it.isNotBlank() }
        val id = instrument.optString("id").takeIf { it.isNotBlank() }

        val snapshot = isin?.let { onvistaSnapshotForIdentifier(it) }
            ?: wkn?.let { onvistaSnapshotForIdentifier(it) }
            ?: id?.let { onvistaSnapshotForInstrumentId(it) }
            ?: return null

        return derivativeDetailsFromSnapshot(snapshot, identifier, instrument)
    }

    private fun onvistaSnapshotForIdentifier(identifier: String): JSONObject? {
        val kind = if (identifier.length == 12 && identifier.take(2).all { it.isLetter() }) "ISIN" else "WKN"
        val url = "https://api.onvista.de/api/v1/derivatives/${kind}%3A$identifier/snapshot"
        return runCatching { JSONObject(httpGet(url)) }.getOrNull()
    }

    private fun onvistaSnapshotForInstrumentId(id: String): JSONObject? {
        val url = "https://api.onvista.de/api/v1/instruments/DERIVATIVE/$id/snapshot"
        return runCatching { JSONObject(httpGet(url)) }.getOrNull()
    }

    private fun searchOnvistaInstrument(identifier: String): JSONObject? {
        val url = "https://api.onvista.de/api/v1/instruments/search?searchValue=${java.net.URLEncoder.encode(identifier, "UTF-8")}"
        val json = JSONObject(httpGet(url))
        val arrays = listOf("instruments", "results", "list", "data")
        for (key in arrays) {
            val arr = json.optJSONArray(key) ?: continue
            val found = firstInstrumentSearchHit(arr, identifier)
            if (found != null) return found
        }
        val buckets = listOf("DERIVATIVE", "derivatives", "STOCK", "stocks", "FUND", "funds", "ETF", "etfs")
        for (key in buckets) {
            val arr = json.optJSONArray(key) ?: continue
            firstInstrumentSearchHit(arr, identifier)?.let { return it }
        }
        return null
    }

    private fun firstInstrumentSearchHit(arr: JSONArray, identifier: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val isin = item.optString("isin")
            val wkn = item.optString("wkn")
            if (
                isin.equals(identifier, true) ||
                wkn.equals(identifier, true)
            ) return item
        }
        return null
    }

    private fun isDerivativeInstrument(instrument: JSONObject): Boolean {
        return instrumentTypeText(instrument).contains("DERIV")
    }

    private fun instrumentTypeText(instrument: JSONObject): String {
        return listOf(
            instrument.optString("type"),
            instrument.optString("entityType"),
            instrument.optString("instrumentType"),
            instrument.optString("assetType"),
            instrument.optString("category"),
            instrument.optString("group")
        ).joinToString(" ").uppercase()
    }

    private fun instrumentSnapshotForSearchHit(instrument: JSONObject): JSONObject? {
        val type = firstPlainString(instrument, listOf("entityType", "type", "instrumentType"))
            ?.uppercase() ?: return null
        val id = firstPlainString(instrument, listOf("id", "value", "entityValue", "idInstrument"))
            ?: return null
        return onvistaSnapshotForInstrument(type, id)
    }

    private fun onvistaSnapshotForInstrument(type: String, id: String): JSONObject? {
        val encodedType = java.net.URLEncoder.encode(type, "UTF-8")
        val encodedId = java.net.URLEncoder.encode(id, "UTF-8")
        val url = "https://api.onvista.de/api/v1/instruments/$encodedType/$encodedId/snapshot"
        return runCatching { JSONObject(httpGet(url)) }.getOrNull()
    }

    private fun instrumentDetailsFromSearchHit(instrument: JSONObject, identifier: String): JSONObject {
        val snapshot = instrumentSnapshotForSearchHit(instrument)
        val instrumentObj = snapshot?.optJSONObject("instrument")
        val name = instrumentObj?.optString("name")?.takeIf { it.isNotBlank() }
            ?: firstPlainString(instrument, listOf("name", "displayName", "shortName", "tinyName"))
            ?: recursiveFindPlainString(snapshot, listOf("name", "instrumentName", "shortName"))
            ?: identifier
        val symbol = firstPlainString(instrument, listOf("symbol", "homeSymbol", "ticker"))
            ?: instrumentObj?.let { firstPlainString(it, listOf("symbol", "homeSymbol", "ticker")) }
        val currency = snapshot?.let {
            recursiveFindPlainString(it, listOf("isoCurrency", "currency", "currencyCode"))
        } ?: firstPlainString(instrument, listOf("isoCurrency", "currency", "currencyCode")) ?: "EUR"

        val details = JSONObject()
            .put("identifier", identifier)
            .put("source", "Onvista")
            .put("name", name)
            .put("underlyingName", name)
            .put("productCurrency", currency)
            .put("underlyingCurrency", currency)

        inferInstrumentAssetType(name, instrument, snapshot)?.let { details.put("productType", it) }
        instrument.optString("isin").takeIf { it.isNotBlank() }?.let { details.put("isin", it) }
        instrument.optString("wkn").takeIf { it.isNotBlank() }?.let { details.put("wkn", it) }
        symbol?.takeIf { it.isNotBlank() }?.let { details.put("underlyingSymbol", it) }
        snapshot?.let { preferredQuotePrice(it) }?.let { details.put("productPrice", it) }
        snapshot?.let { findDividendYieldPercent(it) }?.let { details.put("dividendYield", it) }
        return details
    }

    private fun inferInstrumentAssetType(name: String, instrument: JSONObject, snapshot: JSONObject?): String? {
        val text = listOf(name, instrumentTypeText(instrument), snapshot?.toString().orEmpty()).joinToString(" ").uppercase()
        return when {
            "ETF" in text || "EXCHANGE TRADED FUND" in text || "UCITS" in text -> "etf"
            "CRYPTO" in text || "BITCOIN" in text || "ETHEREUM" in text -> "crypto"
            "FUND" in text && "ETF" !in text -> "etf"
            "STOCK" in text || "SHARE" in text || "EQUITY" in text || "AKTIE" in text -> "stock"
            else -> "stock"
        }
    }

    private fun derivativeDetailsFromSnapshot(snapshot: JSONObject, identifier: String, instrument: JSONObject? = null): JSONObject {
        val details = JSONObject()
        val instrumentObj = snapshot.optJSONObject("instrument")
        val detailObj = snapshot.optJSONObject("derivativesDetails")
        val figureObj = snapshot.optJSONObject("derivativesFigure")
        val name = instrumentObj?.optString("name")?.takeIf { it.isNotBlank() }
            ?: detailObj?.optString("shortName")?.takeIf { it.isNotBlank() }
            ?: detailObj?.optString("officialName")?.takeIf { it.isNotBlank() }
            ?: recursiveFindString(snapshot, listOf("name", "instrumentName", "shortName"))
            ?: instrument?.optString("name")
            ?: ""

        details.put("identifier", identifier)
        details.put("source", "Onvista")
        if (name.isNotBlank()) details.put("name", name)
        instrument?.optString("isin")?.takeIf { it.isNotBlank() }?.let { details.put("isin", it) }
        instrument?.optString("wkn")?.takeIf { it.isNotBlank() }?.let { details.put("wkn", it) }

        inferDerivativeType(name, instrumentObj, detailObj)?.let { details.put("productType", it) }
        inferDerivativeDirection(name, instrumentObj, detailObj)?.let { details.put("direction", it) }
        preferredQuotePrice(snapshot)?.let { details.put("productPrice", it) }
        val statusText = derivativeStatusText(snapshot, instrumentObj, detailObj)
        if (statusText.isNotBlank()) details.put("statusText", statusText)
        if (isKnockedOutStatus(statusText)) details.put("knockedOut", true)

        val pageDetails = onvistaDerivativePageValues(
            instrumentObj?.optJSONObject("urls")?.optString("WEBSITE")?.takeIf { it.isNotBlank() }
                ?: instrument?.optJSONObject("urls")?.optString("WEBSITE")?.takeIf { it.isNotBlank() }
        )
        (recursiveFindNumber(snapshot, listOf("strike", "basispreis", "basePrice", "exercisePrice"), groupedSingleDot = true)
            ?: parseStrikeFromProductName(name)
            ?: pageDetails?.strike)
            ?.let { details.put("strike", it) }
        (recursiveFindNumber(snapshot, listOf("barrier", "knockout", "knockOut", "stopLoss", "koBarrier"), groupedSingleDot = true)
            ?: parseKoFromProductName(name))
            ?.let { details.put("koBarrier", it) }
        val expiry = (detailObj?.optString("datetimeMaturity")?.takeIf { it.isNotBlank() }
            ?: detailObj?.optString("datetimeLastTradingDay")?.takeIf { it.isNotBlank() }
            ?: recursiveFindString(snapshot, listOf("maturity", "expiryDate", "expirationDate", "dueDate", "finalValuationDate", "lastTradingDay")))
            ?.let { normalizeDatePrefix(it) }
        expiry?.let { details.put("expiry", it) }
        (figureObj?.let { parseFlexibleNumber(it.opt("impliedVolatility")) }
            ?: figureObj?.let { parseFlexibleNumber(it.opt("impliedVolatilityBid")) }
            ?: figureObj?.let { parseFlexibleNumber(it.opt("impliedVolatilityAsk")) }
            ?: recursiveFindNumber(snapshot, listOf("volatility", "implVolatility", "impliedVolatility", "vola")))
            ?.let { details.put("volatility", percentValue(it)) }
        val currency = figureObj?.optString("isoCurrencyUnderlying")?.takeIf { it.isNotBlank() }
            ?: figureObj?.optString("isoCurrency")?.takeIf { it.isNotBlank() }
            ?: detailObj?.optString("isoCurrency")?.takeIf { it.isNotBlank() }
            ?: ""
        val productCurrency = figureObj?.optString("isoCurrency")?.takeIf { it.isNotBlank() }
            ?: detailObj?.optString("isoCurrency")?.takeIf { it.isNotBlank() }
            ?: "EUR"
        if (productCurrency.isNotBlank()) details.put("productCurrency", productCurrency)
        (currency.takeIf { it.isNotBlank() } ?: pageDetails?.strikeCurrency)
            ?.let { details.put("underlyingCurrency", it) }
        (explicitInterestRate(snapshot)?.let { percentValue(it) }
            ?: fetchReferenceInterestRate(currency, expiry))
            ?.let { details.put("interestRate", it) }
        (findDividendYieldPercent(snapshot))
            ?.let { details.put("dividendYield", it) }
        if (currency.isNotBlank()) details.put("interestCurrency", currency)
        (parseRatioFromProductName(name)
            ?: parseRatioFromDescription(detailObj)
            ?: recursiveFindNumber(snapshot, listOf("subscriptionRatio", "multiplier", "bezugsverhaeltnis")))
            ?.let { details.put("ratio", it) }
        val underlying = findUnderlyingObject(snapshot) ?: instrument?.let { findUnderlyingObject(it) }
        val underlyingName = underlying?.let {
            firstPlainString(it, listOf("name", "displayName", "tinyName", "shortName", "urlName"))
        } ?: recursiveFindPlainString(snapshot, listOf("underlyingName", "basiswertName", "nameUnderlying", "underlying", "basiswert"))
        val underlyingSymbol = underlying?.let {
            firstPlainString(it, listOf("symbol", "homeSymbol", "ticker"))
        } ?: recursiveFindPlainString(snapshot, listOf("underlyingSymbol", "underlyingTicker"))
        val underlyingIsin = underlying?.let {
            firstPlainString(it, listOf("isin"))
        } ?: recursiveFindPlainString(snapshot, listOf("isinUnderlying", "underlyingIsin"))
        val underlyingWkn = underlying?.let {
            firstPlainString(it, listOf("wkn"))
        } ?: recursiveFindPlainString(snapshot, listOf("wknUnderlying", "underlyingWkn"))

        underlyingName?.let { details.put("underlyingName", it) }
        underlyingSymbol?.let { details.put("underlyingSymbol", it) }
        underlyingIsin?.let { details.put("underlyingIsin", it) }
        underlyingWkn?.let { details.put("underlyingWkn", it) }

        val embeddedUnderlyingPrice = figureObj?.let {
            firstNumber(it, listOf("underlyingPrice", "priceUnderlying", "spot", "underlyingLast"))
        } ?: recursiveFindNumber(snapshot, listOf("underlyingPrice", "priceUnderlying", "underlyingLast"))
        val underlyingPrice = embeddedUnderlyingPrice
            ?: underlying?.let { fetchUnderlyingPrice(it) }
        underlyingPrice?.let { details.put("underlyingPrice", it) }
        val underlyingDividendYield = underlying?.let { fetchUnderlyingDividendYield(it) }
        if (underlyingDividendYield != null && !details.has("dividendYield")) {
            details.put("dividendYield", underlyingDividendYield)
        }

        return details
    }

    private fun derivativeStatusText(snapshot: JSONObject, instrument: JSONObject?, details: JSONObject?): String {
        return listOf(
            instrument?.optString("status").orEmpty(),
            instrument?.optString("tradingStatus").orEmpty(),
            instrument?.optString("state").orEmpty(),
            details?.optString("status").orEmpty(),
            details?.optString("tradingStatus").orEmpty(),
            details?.optString("knockOutStatus").orEmpty(),
            recursiveFindPlainString(snapshot, listOf("status", "tradingStatus", "state", "knockOutStatus")).orEmpty()
        ).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

    private data class OnvistaPageDetails(val strike: Double?, val strikeCurrency: String?)

    private fun onvistaDerivativePageValues(url: String?): OnvistaPageDetails? {
        if (url.isNullOrBlank()) return null
        val html = runCatching { httpGet(url) }.getOrNull() ?: return null
        val basisBlock = Regex("""Basispreis[\s\S]{0,700}""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.value
            ?: return null
        val strike = Regex("""<data\b[^>]*\bvalue="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(basisBlock)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { parseFlexibleNumber(it, groupedSingleDot = true) }
        val currency = Regex("""<span[^>]*>[\s\S]{0,40}?([A-Z]{3})\s*</span>""", RegexOption.IGNORE_CASE)
            .find(basisBlock)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()
        return if (strike != null || currency != null) OnvistaPageDetails(strike, currency) else null
    }

    private fun isKnockedOutStatus(statusText: String): Boolean {
        val text = statusText.uppercase()
        return listOf("KNOCK", "AUSGEKNOCKT", "KNOCKED", "TERMINATED", "EXPIRED", "DELISTED", "FÄLLIG", "FAELLIG")
            .any { text.contains(it) }
    }

    private fun inferDerivativeType(name: String, instrument: JSONObject?, details: JSONObject?): String? {
        val text = listOf(
            name,
            instrument?.optString("entitySubType").orEmpty(),
            instrument?.optString("displayType").orEmpty(),
            details?.optString("productType").orEmpty(),
            details?.optString("derivativeType").orEmpty(),
            details?.optString("type").orEmpty()
        ).joinToString(" ").uppercase()
        return when {
            listOf("KNOCK", "TURBO", "MINI FUTURE", "KO-", "OPEN END TURBO", "UNLIMITED TURBO").any(text::contains) -> "ko"
            listOf("FAKTOR", "FACTOR", "FAKTORZERTIFIKAT", "CONSTANT LEVERAGE").any(text::contains) -> "faktor"
            listOf("OPTIONSSCHEIN", "WARRANT", "CALL WARRANT", "PUT WARRANT").any(text::contains) -> "os"
            // Vorsicht: CALL/PUT allein reicht nicht — könnte auch Faktor sein
            listOf("CALL", "PUT").any(text::contains) && !listOf("FAKTOR", "FACTOR", "TURBO", "KNOCK").any(text::contains) -> "os"
            else -> null
        }
    }

    private fun inferDerivativeDirection(name: String, instrument: JSONObject?, details: JSONObject?): String? {
        val direct = listOf("optionType", "callPut", "direction", "typeOption", "leverageType")
            .firstNotNullOfOrNull { key ->
                details?.optString(key)?.takeIf { it.isNotBlank() }
                    ?: instrument?.optString(key)?.takeIf { it.isNotBlank() }
            }
        val text = listOf(
            direct.orEmpty(),
            name,
            instrument?.optString("entitySubType").orEmpty(),
            instrument?.optString("displayType").orEmpty()
        ).joinToString(" ").uppercase()
        return when {
            Regex("(^|[^A-Z])(PUT|SHORT|BEAR|SELL)([^A-Z]|\$)").containsMatchIn(text) -> "put"
            Regex("(^|[^A-Z])(CALL|LONG|BULL|BUY)([^A-Z]|\$)").containsMatchIn(text) -> "call"
            else -> null
        }
    }

    private fun preferredQuotePrice(snapshot: JSONObject): Double? {
        val quote = snapshot.optJSONObject("quote")
            ?: snapshot.optJSONObject("quoteList")?.optJSONArray("list")?.optJSONObject(0)
            ?: return null
        firstNumber(quote, listOf("last"))?.takeIf { it > 0 }?.let { return it }
        val bid = firstNumber(quote, listOf("bid"))?.takeIf { it > 0 }
        val ask = firstNumber(quote, listOf("ask"))?.takeIf { it > 0 }
        if (bid != null && ask != null) return (bid + ask) / 2.0
        return ask ?: bid ?: firstNumber(quote, listOf("previousLast", "open"))?.takeIf { it > 0 }
    }

    private fun firstNumber(obj: JSONObject, keys: List<String>): Double? {
        for (key in keys) parseFlexibleNumber(obj.opt(key))?.let { return it }
        return null
    }

    private fun fetchUnderlyingPrice(underlying: JSONObject): Double? {
        val type = firstPlainString(underlying, listOf("entityType", "type", "instrumentType"))
            ?.uppercase() ?: return null
        val value = firstPlainString(underlying, listOf("value", "entityValue", "id", "idInstrument"))
            ?: return null
        val encodedType = java.net.URLEncoder.encode(type, "UTF-8")
        val encodedValue = java.net.URLEncoder.encode(value, "UTF-8")
        val url = "https://api.onvista.de/api/v1/instruments/$encodedType/$encodedValue/snapshot"
        val snapshot = runCatching { JSONObject(httpGet(url)) }.getOrNull() ?: return null
        return preferredQuotePrice(snapshot)
    }

    private fun fetchUnderlyingDividendYield(underlying: JSONObject): Double? {
        val type = firstPlainString(underlying, listOf("entityType", "type", "instrumentType"))
            ?.uppercase() ?: return null
        val value = firstPlainString(underlying, listOf("value", "entityValue", "id", "idInstrument"))
            ?: return null
        val encodedType = java.net.URLEncoder.encode(type, "UTF-8")
        val encodedValue = java.net.URLEncoder.encode(value, "UTF-8")
        val url = "https://api.onvista.de/api/v1/instruments/$encodedType/$encodedValue/snapshot"
        val snapshot = runCatching { JSONObject(httpGet(url)) }.getOrNull() ?: return null
        return findDividendYieldPercent(snapshot)
    }

    private fun firstPlainString(obj: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank() && value != "null") return value.trim()
        }
        return null
    }

    private fun findUnderlyingObject(value: Any?): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("finderUnderlying")?.let { return it }
                value.optJSONObject("derivativesUnderlyingList")
                    ?.optJSONArray("list")
                    ?.optJSONObject(0)
                    ?.optJSONObject("instrument")
                    ?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = value.opt(key)
                    val normalized = key.lowercase()
                    if (item is JSONObject && (
                        normalized == "underlying" ||
                        normalized == "underlyingentity" ||
                        normalized == "underlyinginstrument" ||
                        normalized == "baseinstrument" ||
                        normalized == "basiswert" ||
                        normalized == "finderunderlying"
                    )) {
                        return item
                    }
                    findUnderlyingObject(item)?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findUnderlyingObject(value.opt(index))?.let { return it }
            }
        }
        return null
    }

    private fun recursiveFindPlainString(value: Any?, keys: List<String>): String? {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val item = value.opt(key)
                    if (keys.any { key.equals(it, true) } && item is String && item.isNotBlank()) {
                        return item.trim()
                    }
                    recursiveFindPlainString(item, keys)?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                recursiveFindPlainString(value.opt(index), keys)?.let { return it }
            }
        }
        return null
    }

    private fun httpGet(url: String): String {
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
            conn.setRequestProperty("User-Agent", "TradingBuddy-Android")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("Onvista HTTP $code")
            return text
        } finally {
            conn?.disconnect()
        }
    }

    private fun recursiveFindNumber(value: Any?, keys: List<String>, groupedSingleDot: Boolean = false): Double? {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val item = value.opt(key)
                    val noisyDerivedValue = key.contains("difference", true) ||
                        key.contains("distance", true) ||
                        key.contains("pct", true) ||
                        key.contains("percent", true)
                    if (!noisyDerivedValue && keys.any { key.contains(it, true) }) {
                        parseFlexibleNumber(item, groupedSingleDot)?.let { return it }
                        if (item is JSONObject) parseFlexibleNumber(item.opt("value"), groupedSingleDot)?.let { return it }
                    }
                    recursiveFindNumber(item, keys, groupedSingleDot)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) recursiveFindNumber(value.opt(i), keys, groupedSingleDot)?.let { return it }
        }
        return null
    }

    private fun findDividendYieldPercent(value: Any?): Double? {
        fun normalize(raw: Double?): Double? {
            val pct = raw?.let { percentValue(it) } ?: return null
            return pct.takeIf { it >= 0.0 && it <= 20.0 }
        }
        when (value) {
            is JSONObject -> {
                value.optJSONObject("stocksCnFundamentalList")
                    ?.optJSONArray("list")
                    ?.let { list ->
                        val currentYear = java.time.LocalDate.now().year
                        var best: Pair<Int, Double>? = null
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            val year = item.optInt("idYear", 0).takeIf { it > 0 }
                                ?: Regex("""\d{4}""").find(item.optString("label"))?.value?.toIntOrNull()
                                ?: continue
                            if (year > currentYear) continue
                            val yield = normalize(parseFlexibleNumber(item.opt("cnDivYield"))) ?: continue
                            if (best == null || year > best!!.first) best = year to yield
                        }
                        best?.second?.let { return it }
                    }
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val item = value.opt(key)
                    val k = key.lowercase()
                    val isYieldKey = (k.contains("dividend") && (k.contains("yield") || k.contains("return") || k.contains("pct") || k.contains("percent"))) ||
                        k.contains("dividendenrendite") ||
                        k.contains("dividendyield") ||
                        k.contains("divyield")
                    if (isYieldKey) {
                        normalize(parseFlexibleNumber(item))?.let { return it }
                        if (item is JSONObject) normalize(parseFlexibleNumber(item.opt("value")))?.let { return it }
                    }
                    findDividendYieldPercent(item)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                findDividendYieldPercent(value.opt(i))?.let { return it }
            }
        }
        return null
    }

    private fun recursiveFindString(value: Any?, keys: List<String>): String? {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val item = value.opt(key)
                    if (keys.any { key.contains(it, true) }) {
                        val text = item?.toString()?.trim().orEmpty()
                        if (text.isNotBlank() && text != "null") return text
                    }
                    recursiveFindString(item, keys)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) recursiveFindString(value.opt(i), keys)?.let { return it }
        }
        return null
    }

    private fun parseFlexibleNumber(value: Any?, groupedSingleDot: Boolean = false): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                var text = value.replace("%", "").trim().replace(Regex("""[\s\u00a0\u202f]"""), "")
                text = when {
                    text.contains(",") && text.contains(".") ->
                        if (text.lastIndexOf(",") > text.lastIndexOf(".")) text.replace(".", "").replace(",", ".")
                        else text.replace(",", "")
                    text.contains(",") -> text.replace(",", ".")
                    groupedSingleDot && Regex("""[+-]?\d{1,3}\.\d{3}""").matches(text) -> text.replace(".", "")
                    Regex("""[+-]?\d{1,3}(?:\.\d{3}){2,}""").matches(text) -> text.replace(".", "")
                    else -> text
                }
                text.toDoubleOrNull()
            }
            else -> null
        }
    }

    private fun percentValue(value: Double): Double = if (kotlin.math.abs(value) <= 1.0) value * 100.0 else value

    private fun parseStrikeFromProductName(name: String): Double? {
        val slashParts = name.split("/").map { it.trim() }
        val strikeFromSlash = slashParts
            .drop(2)
            .asSequence()
            .filterNot { Regex("""^\d{1,2}\.\d{1,2}\.\d{2,4}$""").matches(it) }
            .mapNotNull { part ->
                if (!Regex("""^[+-]?\d+(?:[.,]\d+)?$""").matches(part)) null
                else parseFlexibleNumber(part, groupedSingleDot = true)
            }
            .firstOrNull { kotlin.math.abs(it) > 1.0 }
        if (strikeFromSlash != null) return strikeFromSlash

        val slashNumbers = slashParts
            .drop(2)
            .flatMap { part -> Regex("""\d+(?:[.,]\d+)*""").findAll(part).map { it.value }.toList() }
            .mapNotNull { parseFlexibleNumber(it, groupedSingleDot = true) }
            .filter { kotlin.math.abs(it) > 1.0 }
        if (slashNumbers.isNotEmpty()) return slashNumbers.first()

        val cleaned = name
            .replace(Regex("""\b[A-Z]{2}\d[A-Z0-9]{9}\d\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b[A-Z0-9]{6}\b"""), " ")
            .replace(Regex("""\b[A-Z]{1,4}[- ]?,\d{1,4}\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(0?[1-9]|1[0-2])/(\d{2}|\d{4})\b"""), " ")
            .replace(Regex("""\b\d{1,2}\.\d{1,2}\.\d{2,4}\b"""), " ")
            .replace(Regex("""\b\d+(?:[.,]\d+)?x\b""", RegexOption.IGNORE_CASE), " ")
        return Regex("""\d+(?:[.,]\d+)*""").findAll(cleaned)
            .mapNotNull { parseFlexibleNumber(it.value, groupedSingleDot = true) }
            .filter { kotlin.math.abs(it) >= 1.0 }
            .lastOrNull()
    }

    private fun parseRatioFromProductName(name: String): Double? {
        val parts = name.split("/")
        val slashRatio = parts
            .asSequence()
            .map { it.trim() }
            .filter { Regex("""^[+-]?0?[.,]\d+$""").matches(it) }
            .mapNotNull { parseFlexibleNumber(it) }
            .lastOrNull { kotlin.math.abs(it) > 0.0 && kotlin.math.abs(it) <= 1.0 }
        if (slashRatio != null) return slashRatio

        return parts.mapNotNull { part ->
            val clean = part.trim()
            if (Regex("""^\d{1,2}\.\d{1,2}\.\d{2,4}$""").matches(clean)) null
            else parseFlexibleNumber(clean)
        }.firstOrNull { kotlin.math.abs(it) > 0.0 && kotlin.math.abs(it) <= 1.0 }
    }

    private fun parseKoFromProductName(name: String): Double? {
        if (!name.contains("KO", true) && !name.contains("TURBO", true) && !name.contains("KNOCK", true)) return null
        val cleaned = name
            .replace(Regex("""\b[A-Z]{2}\d[A-Z0-9]{9}\d\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b[A-Z0-9]{6}\b"""), " ")
            .replace(Regex("""\b(0?[1-9]|1[0-2])/(\d{2}|\d{4})\b"""), " ")
            .replace(Regex("""\b\d+(?:[.,]\d+)?x\b""", RegexOption.IGNORE_CASE), " ")
        return Regex("""\d+(?:[.,]\d+)*""").findAll(cleaned)
            .mapNotNull { parseFlexibleNumber(it.value, groupedSingleDot = true) }
            .filter { kotlin.math.abs(it) >= 1.0 }
            .lastOrNull()
    }

    private fun parseRatioFromDescription(details: JSONObject?): Double? {
        val desc = details?.optJSONArray("description") ?: return null
        for (i in 0 until desc.length()) {
            val text = desc.optJSONObject(i)?.optString("value").orEmpty()
            val match = Regex("""Bezugsverh[aä]ltnis\s+von\s+([0-9]+(?:[,.][0-9]+)?)""", RegexOption.IGNORE_CASE).find(text)
            if (match != null) return parseFlexibleNumber(match.groupValues[1])
        }
        return null
    }

    private fun explicitInterestRate(snapshot: JSONObject): Double? {
        val figure = snapshot.optJSONObject("derivativesFigure")
        val details = snapshot.optJSONObject("derivativesDetails")
        val directKeys = listOf("interestRate", "riskFreeRate", "interest", "riskFreeInterestRate")
        for (source in listOf(figure, details, snapshot)) {
            if (source == null) continue
            for (key in directKeys) {
                parseFlexibleNumber(source.opt(key))?.let { return it }
            }
        }
        return null
    }

    private data class RateTenor(val label: String, val days: Long, val ecbCode: String, val treasuryCode: String)

    private val rateTenors = listOf(
        RateTenor("3M", 90, "SR_3M", "BC_3MONTH"),
        RateTenor("6M", 180, "SR_6M", "BC_6MONTH"),
        RateTenor("1Y", 365, "SR_1Y", "BC_1YEAR"),
        RateTenor("2Y", 730, "SR_2Y", "BC_2YEAR"),
        RateTenor("3Y", 1095, "SR_3Y", "BC_3YEAR"),
        RateTenor("5Y", 1825, "SR_5Y", "BC_5YEAR"),
        RateTenor("7Y", 2555, "SR_7Y", "BC_7YEAR"),
        RateTenor("10Y", 3650, "SR_10Y", "BC_10YEAR")
    )

    private fun fetchReferenceInterestRate(currency: String, expiry: String?): Double? {
        val days = expiry
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).coerceAtLeast(1) }
            ?: 365L
        val tenor = rateTenors.minByOrNull { kotlin.math.abs(it.days - days) } ?: return null
        return when (currency.uppercase()) {
            "USD" -> fetchUsdTreasuryRate(tenor)
            "EUR", "" -> fetchEurYieldCurveRate(tenor)
            else -> null
        }
    }

    private fun fetchEurYieldCurveRate(tenor: RateTenor): Double? {
        val url = "https://data-api.ecb.europa.eu/service/data/YC/B.U2.EUR.4F.G_N_A.SV_C_YM.${tenor.ecbCode}?lastNObservations=1"
        val text = runCatching { httpGet(url) }.getOrNull() ?: return null
        return Regex("""<generic:ObsValue\s+value="([^"]+)"""").find(text)
            ?.groupValues?.getOrNull(1)
            ?.let { parseFlexibleNumber(it) }
    }

    private fun fetchUsdTreasuryRate(tenor: RateTenor): Double? {
        val now = LocalDate.now()
        val ym = "%04d%02d".format(now.year, now.monthValue)
        val url = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value_month=$ym"
        val text = runCatching { httpGet(url) }.getOrNull() ?: return null
        val entries = Regex("""<m:properties\b[\s\S]*?</m:properties>""").findAll(text).map { it.value }.toList()
        val last = entries.lastOrNull() ?: return null
        return Regex("""<d:${tenor.treasuryCode}[^>]*>([^<]+)</d:${tenor.treasuryCode}>""").find(last)
            ?.groupValues?.getOrNull(1)
            ?.let { parseFlexibleNumber(it) }
    }

    private fun normalizeDatePrefix(value: String): String? {
        val match = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(value)
        if (match != null) return match.value
        val de = Regex("""(\d{2})\.(\d{2})\.(\d{4})""").find(value) ?: return null
        return "${de.groupValues[3]}-${de.groupValues[2]}-${de.groupValues[1]}"
    }

    // ── Lifecycle: Cloud-Sync beim App-in-den-Hintergrund ──────────────────
    override fun onPause() {
        super.onPause()
        try {
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {}
        if (pageLoaded) {
            webView.evaluateJavascript(
                "if(typeof _awPush==='function')_awPush();", null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pageLoaded) {
            webView.evaluateJavascript(
                "if(typeof _awPull==='function')_awPull();", null)
        }
    }

    // ── Screenshot-Picker für Appwrite Storage Upload ───────────────────────
    private var pendingScreenshotTradeId: String? = null
    private val screenshotPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val tradeId = pendingScreenshotTradeId.also { pendingScreenshotTradeId = null } ?: ""
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val fname = "screenshot_${System.currentTimeMillis()}.jpg"
                val qId   = org.json.JSONObject.quote(tradeId)
                val qMime = org.json.JSONObject.quote(mime)
                val qName = org.json.JSONObject.quote(fname)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(typeof onNativeScreenshotPicked==='function')" +
                        "onNativeScreenshotPicked('$b64',$qMime,$qName,$qId);", null)
                }
            } catch (_: Exception) {}
        }.start()
    }

    @android.webkit.JavascriptInterface
    fun pickScreenshotForTrade(tradeId: String) {
        runOnUiThread {
            pendingScreenshotTradeId = tradeId
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            try { screenshotPickerLauncher.launch(intent) }
            catch (_: Exception) { pendingScreenshotTradeId = null }
        }
    }

    private fun notifyKeyboardHeight(keyboardCss: Int) {
        if (!pageLoaded) return
        runOnUiThread {
            webView.evaluateJavascript("if(typeof onKeyboardHeight==='function')onKeyboardHeight($keyboardCss);", null)
        }
    }

    private fun applyInsetsToWebPage() {
        if (!pageLoaded) return
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val config = resources.configuration
        val widthDp = if (config.screenWidthDp > 0) {
            config.screenWidthDp.toFloat()
        } else {
            (webView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / density
        }
        val heightDp = if (config.screenHeightDp > 0) {
            config.screenHeightDp.toFloat()
        } else {
            (webView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / density
        }
        val minDp = minOf(widthDp, heightDp)
        val minPhysicalPx = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
        val tinyPhysicalScreen = minPhysicalPx <= 800
        val smallViewport = minDp < 390f && tinyPhysicalScreen
        val compactSmall = minDp <= 339f && tinyPhysicalScreen
        val comfortPhone = false
        val viewportContent = if (smallViewport) {
            "width=460, viewport-fit=cover"
        } else {
            "width=device-width, initial-scale=1.0, viewport-fit=cover"
        }
        val script = """
            document.documentElement.style.setProperty('--status-inset','${statusInsetCss}px');
            document.documentElement.style.setProperty('--nav-inset','${navigationInsetCss}px');
            document.body.classList.toggle('tb-compact-small',$compactSmall);
            document.body.classList.toggle('tb-small-viewport',$smallViewport);
            document.body.classList.toggle('tb-comfort-phone',$comfortPhone);
            document.documentElement.setAttribute('data-android-dp','${widthDp.toInt()}x${heightDp.toInt()}');
            document.documentElement.setAttribute('data-android-px','${displayMetrics.widthPixels}x${displayMetrics.heightPixels}');
            document.documentElement.setAttribute('data-compact-small','$compactSmall');
            document.documentElement.setAttribute('data-small-viewport','$smallViewport');
            document.documentElement.setAttribute('data-comfort-phone','$comfortPhone');
            if($smallViewport) document.documentElement.setAttribute('data-small-layout-viewport','460');
            else document.documentElement.removeAttribute('data-small-layout-viewport');
            (function(){
              var viewport=document.querySelector('meta[name="viewport"]');
              if(viewport) viewport.setAttribute('content',${JSONObject.quote(viewportContent)});
              var style=document.getElementById('tb-native-compact-style');
              if(style) style.parentNode.removeChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
}
