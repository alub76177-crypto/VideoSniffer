package com.videosniffer.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity(), VideoDetectionManager.Listener {

    companion object {
        @Volatile
        var sharedManager: VideoDetectionManager? = null

        @Volatile
        var activeTabId: Long = 1L

        private val tabCounter = AtomicLong(1L)

        private const val REQ_HISTORY = 41
        private const val DEFAULT_STATUS = "Media links are detected on a best-effort basis."
    }

    private val tabId = tabCounter.getAndIncrement()

    // The WebView only exists while a page is open. Going Home destroys it (stops any
    // playing video, like a browser's "home"), and opening a page creates a fresh one.
    private var webView: WebView? = null

    private lateinit var content: FrameLayout
    private lateinit var homeView: View
    private lateinit var homeInput: EditText
    private lateinit var addressRow: View
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var countButton: Button
    private lateinit var manager: VideoDetectionManager
    private lateinit var history: HistoryStore

    private var isHome = true
    private var currentPageUrl = ""
    private var currentPageTitle = ""
    private var docStartInstalled = false

    @Volatile
    private var browserUserAgent = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(250, 250, 252)
        window.navigationBarColor = Color.rgb(250, 250, 252)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        manager = sharedManager ?: VideoDetectionManager(this).also {
            // Sirf WebView ke observed requests detect karo.
            // Extra automatic verification requests mat bhejo.
            it.probeEnabled = false
            sharedManager = it
        }

        history = HistoryStore(this)

        activeTabId = tabId

        buildUi()
        setupServiceWorkerSniffing()

        // App opens on the Home page.
        showHome()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 252))
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }

        val title = TextView(this).apply {
            text = "Video Sniffer Browser"
            textSize = 20f
            setTextColor(Color.rgb(28, 30, 38))
            setPadding(dp(3), dp(3), dp(3), dp(8))
        }

        root.addView(title)

        // ---- address bar (hidden on Home) ----
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressRow = row

        address = EditText(this).apply {
            hint = "Search Google or enter URL"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            setPadding(dp(12), 0, dp(8), 0)
            setSelectAllOnFocus(false)
        }

        row.addView(
            address,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val go = Button(this).apply {
            text = "Go"
            setOnClickListener { submitAddress(address) }
        }

        row.addView(
            go,
            LinearLayout.LayoutParams(dp(64), dp(48))
        )

        root.addView(row)

        address.setOnEditorActionListener { _, actionId, event ->
            if (isGoAction(actionId, event)) {
                submitAddress(address)
                true
            } else {
                false
            }
        }

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            visibility = View.GONE
        }

        root.addView(
            progress,
            LinearLayout.LayoutParams(-1, dp(3))
        )

        // ---- main content: Home page OR WebView ----
        content = FrameLayout(this)
        homeView = buildHomeView()
        content.addView(
            homeView,
            FrameLayout.LayoutParams(-1, -1)
        )

        root.addView(
            content,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // ---- bottom bar ----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = Button(this).apply {
            text = "\u2039"
            contentDescription = "Back"
            setOnClickListener { goBackOrHome() }
        }

        val home = Button(this).apply {
            text = "Home"
            setOnClickListener { showHome() }
        }

        countButton = Button(this).apply {
            text = "Links (0)"
            setOnClickListener {
                startActivity(
                    Intent(
                        this@MainActivity,
                        SniffedVideosActivity::class.java
                    ).putExtra("tabId", tabId)
                )
            }
        }

        val historyButton = Button(this).apply {
            text = "History"
            setOnClickListener {
                startActivityForResult(
                    Intent(this@MainActivity, HistoryActivity::class.java),
                    REQ_HISTORY
                )
            }
        }

        val reload = Button(this).apply {
            text = "\u21BB"
            contentDescription = "Reload"
            setOnClickListener { if (!isHome) webView?.reload() }
        }

        bottom.addView(back, LinearLayout.LayoutParams(0, dp(46), 0.7f))
        bottom.addView(home, LinearLayout.LayoutParams(0, dp(46), 1f))
        bottom.addView(countButton, LinearLayout.LayoutParams(0, dp(46), 1.3f))
        bottom.addView(historyButton, LinearLayout.LayoutParams(0, dp(46), 1.1f))
        bottom.addView(reload, LinearLayout.LayoutParams(0, dp(46), 0.7f))

        root.addView(bottom)

        status = TextView(this).apply {
            text = DEFAULT_STATUS
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(3), dp(4), dp(2))
        }

        root.addView(status)

        setContentView(root)
    }

    /** Home page: a search/URL box and a short hint (same idea as Kitty Browser's home). */
    private fun buildHomeView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(60))
        }

        box.addView(
            TextView(this).apply {
                text = "Video Sniffer"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(75, 65, 130))
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(210, 210, 220))
            }
        }

        homeInput = EditText(this).apply {
            hint = "Search or enter URL"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            background = null
            setPadding(dp(2), 0, dp(6), 0)
        }

        homeInput.setOnEditorActionListener { _, actionId, event ->
            if (isGoAction(actionId, event)) {
                submitAddress(homeInput)
                true
            } else {
                false
            }
        }

        bar.addView(homeInput, LinearLayout.LayoutParams(0, dp(50), 1f))

        bar.addView(
            Button(this).apply {
                text = "Go"
                setOnClickListener { submitAddress(homeInput) }
            },
            LinearLayout.LayoutParams(dp(64), dp(44))
        )

        box.addView(bar, LinearLayout.LayoutParams(-1, dp(52)))

        box.addView(
            TextView(this).apply {
                text = "Koi bhi video site kholo. Video chalte hi uska link \"Links\" me pakda jayega."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.DKGRAY)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        )

        return box
    }

    private fun isGoAction(actionId: Int, event: KeyEvent?): Boolean {
        val enterPressed =
            event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
        return actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_SEARCH ||
            enterPressed
    }

    // ------------------------------------------------------------------ Home / navigation

    private fun showHome() {
        isHome = true

        destroyWebView()

        // Naye page se pehle purane links saaf.
        manager.clear(tabId)

        currentPageUrl = ""
        currentPageTitle = ""
        address.setText("")
        homeInput.setText("")

        progress.visibility = View.GONE
        addressRow.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        status.text = DEFAULT_STATUS
        countButton.text = "Links (0)"

        hideKeyboard(homeInput)
        homeInput.clearFocus()
    }

    private fun showWebUi() {
        isHome = false
        homeView.visibility = View.GONE
        addressRow.visibility = View.VISIBLE
    }

    private fun goBackOrHome() {
        val wv = webView
        if (!isHome && wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (!isHome) {
            showHome()
        }
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.removeJavascriptInterface("VideoSnifferBridge")
            content.removeView(wv)
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun createWebView(): WebView {
        val wv = WebView(this)
        content.addView(wv, FrameLayout.LayoutParams(-1, -1))
        setupWebView(wv)
        webView = wv
        return wv
    }

    private fun submitAddress(from: EditText) {
        hideKeyboard(from)
        from.clearFocus()
        openAddress(from.text.toString().trim())
    }

    private fun openAddress(input: String) {
        if (input.isBlank()) return

        val target = when {
            input.startsWith("https://", true) ||
            input.startsWith("http://", true) -> {
                input
            }

            input.contains(".") && !input.contains(" ") -> {
                "https://$input"
            }

            else -> {
                "https://www.google.com/search?q=" +
                    URLEncoder.encode(input, "UTF-8")
            }
        }

        manager.clear(tabId)
        showWebUi()
        address.setText(target)
        (webView ?: createWebView()).loadUrl(target)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ------------------------------------------------------------------ WebView

    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            mediaPlaybackRequiresUserGesture = false

            loadWithOverviewMode = true
            useWideViewPort = true

            javaScriptCanOpenWindowsAutomatically = false

            mixedContentMode =
                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // "; wv" hatao taaki sites ise normal browser samjhein.
        wv.settings.userAgentString =
            wv.settings.userAgentString.orEmpty().replace("; wv", "")

        browserUserAgent =
            wv.settings.userAgentString.orEmpty()

        wv.addJavascriptInterface(
            PageVideoBridge(),
            "VideoSnifferBridge"
        )

        // Iframe players (embedded video sites) need third-party cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // Inject the sniffer at document start in ALL frames (main page + iframes).
        docStartInstalled = false
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    SnifferScript.JS,
                    setOf("*")
                )
                docStartInstalled = true
            }
        } catch (_: Throwable) {
            docStartInstalled = false
        }

        wv.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress

                progress.visibility =
                    if (!isHome && newProgress in 1..99) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }

            override fun onReceivedTitle(
                view: WebView?,
                title: String?
            ) {
                currentPageTitle = title.orEmpty()
            }
        }

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                val scheme = request?.url?.scheme?.lowercase().orEmpty()

                // Normal http/https navigation allow karo.
                if (scheme == "http" || scheme == "https") return false

                // intent:// links: fallback URL ho to wahi kholo.
                if (scheme == "intent") {
                    try {
                        val fallback = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            .getStringExtra("browser_fallback_url")
                        if (!fallback.isNullOrBlank()) view?.loadUrl(fallback)
                    } catch (_: Exception) {
                    }
                    return true
                }

                // bilibili:// jaise app deep links block karo (ERR_UNKNOWN_URL_SCHEME se bachne ke liye).
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {

                val url = request?.url?.toString().orEmpty()

                if (url.isNotBlank()) {
                    manager.onRequest(
                        tabId,
                        url,
                        request?.method ?: "GET",
                        request?.requestHeaders,
                        currentPageUrl,
                        currentPageTitle,
                        browserUserAgent
                    )
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                if (url.isNullOrEmpty() || url == "about:blank") return

                currentPageUrl = url
                address.setText(url)

                // Naya page shuru: purane page ke links hata do (Kitty Browser jaisa).
                manager.clear(tabId)

                injectSniffer(view)
            }

            // Catches resources that do not go through shouldInterceptRequest.
            override fun onLoadResource(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) {
                    manager.onRequest(
                        tabId,
                        url,
                        "GET",
                        null,
                        currentPageUrl,
                        currentPageTitle,
                        browserUserAgent
                    )
                }
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                if (url.isNullOrEmpty() || url == "about:blank") return

                currentPageUrl = url
                address.setText(url)

                // History me page save karo (http/https only).
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    val title = view?.title.orEmpty().ifEmpty { url }
                    Thread { history.add(url, title) }.start()
                }

                injectSniffer(view)
            }
        }
    }

    /** Fallback for old WebViews without DOCUMENT_START_SCRIPT (main frame only). */
    private fun injectSniffer(view: WebView?) {
        if (docStartInstalled) return
        view?.evaluateJavascript(SnifferScript.JS, null)
    }

    /**
     * Service workers fetch media outside the page, so shouldInterceptRequest never sees it.
     * This client receives those requests; they are attributed to the active tab.
     */
    private fun setupServiceWorkerSniffing() {
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (url.isNotBlank()) {
                            sharedManager?.onRequest(
                                activeTabId,
                                url,
                                request?.method ?: "GET",
                                request?.requestHeaders,
                                currentPageUrl,
                                currentPageTitle,
                                browserUserAgent
                            )
                        }
                        return null
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private inner class PageVideoBridge {

        @JavascriptInterface
        fun onMediaReport(
            url: String,
            mime: String?,
            source: String?,
            playing: Boolean,
            title: String?,
            pageUrl: String?
        ) {
            manager.onPageMediaReport(
                tabId,
                url,
                mime,
                playing,
                source,
                pageUrl ?: currentPageUrl,
                title ?: currentPageTitle,
                browserUserAgent
            )
        }

        @JavascriptInterface
        fun onBlobVideo(pageUrl: String?) {
            runOnUiThread {
                status.text =
                    "Player blob/MediaSource use kar raha hai - real stream URL network requests se pakda jayega."
            }
        }

        @JavascriptInterface
        fun onVideoUrl(
            url: String,
            playing: Boolean,
            title: String?,
            pageUrl: String?
        ) {
            manager.onVideoElementReport(
                tabId,
                url,
                playing,
                pageUrl,
                title,
                browserUserAgent
            )
        }
    }

    // ------------------------------------------------------------------ callbacks

    override fun onMediaDetected(media: DetectedMedia) {
        if (media.tabId != tabId) return

        countButton.text =
            "Links (${manager.getItems(tabId).size})"

        status.text =
            "Detected ${media.type.label}: ${media.confidence.label} confidence"
    }

    override fun onMediaListChanged(tabId: Long) {
        if (tabId == this.tabId) {
            countButton.text =
                "Links (${manager.getItems(tabId).size})"
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HISTORY && resultCode == RESULT_OK) {
            val url = data?.getStringExtra("selected_url").orEmpty()
            if (url.isNotBlank()) openAddress(url)
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density + 0.5f
        ).toInt()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        val wv = webView
        when {
            !isHome && wv != null && wv.canGoBack() -> wv.goBack()
            !isHome -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        activeTabId = tabId
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }
}
