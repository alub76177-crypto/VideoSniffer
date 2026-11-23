package com.videosniffer.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JavaScript injected at document start into every page (like MainActivity does for SnifferScript).
 *
 * It hooks console.log/info/warn/error/debug, window 'error' and unhandled promise
 * rejections, and forwards everything to the VideoSnifferDevBridge JavascriptInterface.
 * The Java bridge can appear a moment after document start, so messages are queued
 * and flushed periodically (same trick SnifferScript uses).
 *
 * NOTE: no dollar-sign characters inside this string (Kotlin raw string templates).
 */
object DevToolsConsoleScript {
    const val JS = """
(function () {
  if (window.__vsDevHook) return;
  window.__vsDevHook = true;
  var pending = [];
  function flush() {
    try {
      var B = window.VideoSnifferDevBridge;
      if (!B) return;
      while (pending.length) {
        var a = pending.shift();
        try { B.onConsole(a[0], a[1]); } catch (e) {}
      }
    } catch (e) {}
  }
  setInterval(flush, 700);
  function fmt(v, depth) {
    try {
      if (v === null) return 'null';
      if (v === undefined) return 'undefined';
      var t = typeof v;
      if (t === 'string') return v.length > 2000 ? v.substring(0, 2000) + '...' : v;
      if (t === 'number' || t === 'boolean') return String(v);
      if (t === 'function') return '[Function ' + (v.name || 'anonymous') + ']';
      if (t === 'symbol') return v.toString();
      if (v instanceof Error) return (v.name || 'Error') + ': ' + (v.message || '');
      if (v && v.nodeType) return '<' + String(v.nodeName || '').toLowerCase() + (v.id ? ' id=' + v.id : '') + '>';
      if (depth > 2) return Array.isArray(v) ? '[Array]' : '[Object]';
      if (Array.isArray(v)) {
        var ps = [];
        for (var i = 0; i < v.length && i < 20; i++) ps.push(fmt(v[i], depth + 1));
        return '[' + ps.join(', ') + (v.length > 20 ? ', ...' : '') + ']';
      }
      var ks = Object.keys(v);
      var qs = [];
      for (var j = 0; j < ks.length && j < 12; j++) qs.push(ks[j] + ': ' + fmt(v[ks[j]], depth + 1));
      return '{' + qs.join(', ') + (ks.length > 12 ? ', ...' : '') + '}';
    } catch (e) { return '[unserializable]'; }
  }
  function send(level, args) {
    try {
      var parts = [];
      for (var i = 0; i < args.length; i++) parts.push(fmt(args[i], 0));
      pending.push([String(level), parts.join(' ').substring(0, 3000)]);
      flush();
    } catch (e) {}
  }
  var levels = ['log', 'info', 'warn', 'error', 'debug'];
  for (var li = 0; li < levels.length; li++) {
    (function (level) {
      var orig = console[level] ? console[level].bind(console) : function () {};
      console[level] = function () {
        try { send(level, arguments); } catch (e) {}
        try { orig.apply(console, arguments); } catch (e2) {}
      };
    })(levels[li]);
  }
  window.addEventListener('error', function (e) {
    try {
      var msg = e.message || 'Script error';
      if (e.filename) msg += ' (' + String(e.filename).split('/').pop() + ':' + (e.lineno || 0) + ')';
      send('error', [msg]);
    } catch (er) {}
  });
  window.addEventListener('unhandledrejection', function (e) {
    try { send('error', ['Unhandled rejection: ' + fmt(e.reason, 0)]); } catch (er) {}
  });
})();
"""
}

/** Small JS snippets the DevTools panel evaluates on demand against the loaded page. */
object DevToolsSnippets {

    // DOM overview
    const val STATS_JS = """
(function () {
  var d = document;
  return JSON.stringify({
    title: d.title,
    url: location.href,
    charset: d.characterSet,
    elements: d.getElementsByTagName('*').length,
    links: (d.links || []).length,
    images: (d.images || []).length,
    scripts: (d.scripts || []).length,
    stylesheets: (d.styleSheets || []).length,
    iframes: d.querySelectorAll('iframe').length,
    forms: (d.forms || []).length,
    docHeight: d.documentElement.scrollHeight,
    readyState: d.readyState
  });
})();
"""

    // <img> list with natural size
    const val IMAGES_JS = """
(function () {
  return JSON.stringify(Array.prototype.map.call(document.images, function (img) {
    return {
      src: String(img.currentSrc || img.src || '').substring(0, 400),
      w: img.naturalWidth,
      h: img.naturalHeight,
      alt: String(img.alt || '').substring(0, 60)
    };
  }).slice(0, 300));
})();
"""

    // <script> list
    const val SCRIPTS_JS = """
(function () {
  return JSON.stringify(Array.prototype.map.call(document.scripts, function (s) {
    return { src: String(s.src || '[inline]').substring(0, 300), len: (s.text || '').length };
  }).slice(0, 300));
})();
"""

    // <a> list
    const val LINKS_JS = """
(function () {
  return JSON.stringify(Array.prototype.map.call(document.links, function (a) {
    return {
      href: String(a.href || '').substring(0, 400),
      text: String(a.textContent || '').trim().substring(0, 60)
    };
  }).slice(0, 300));
})();
"""

    // <meta> list
    const val METAS_JS = """
(function () {
  return JSON.stringify(Array.prototype.map.call(document.querySelectorAll('meta'), function (m) {
    return {
      k: m.getAttribute('name') || m.getAttribute('property') || m.getAttribute('http-equiv') || '',
      v: String(m.getAttribute('content') || '').substring(0, 200)
    };
  }).slice(0, 200));
})();
"""

    // localStorage dump
    const val LS_JS = """
(function () {
  try {
    var out = [];
    for (var i = 0; i < localStorage.length && i < 200; i++) {
      var k = localStorage.key(i);
      out.push({ k: k, v: String(localStorage.getItem(k)).substring(0, 300) });
    }
    return JSON.stringify({ count: localStorage.length, items: out });
  } catch (e) { return JSON.stringify({ error: String(e) }); }
})();
"""

    // sessionStorage dump
    const val SS_JS = """
(function () {
  try {
    var out = [];
    for (var i = 0; i < sessionStorage.length && i < 200; i++) {
      var k = sessionStorage.key(i);
      out.push({ k: k, v: String(sessionStorage.getItem(k)).substring(0, 300) });
    }
    return JSON.stringify({ count: sessionStorage.length, items: out });
  } catch (e) { return JSON.stringify({ error: String(e) }); }
})();
"""

    // key counts only (fast, for the summary line)
    const val STORAGE_COUNT_JS = """
(function () {
  var o = { ls: -1, ss: -1 };
  try { o.ls = localStorage.length; } catch (e) {}
  try { o.ss = sessionStorage.length; } catch (e) {}
  return JSON.stringify(o);
})();
"""

    // performance resource timings (real status codes + transfer sizes where available)
    const val PERF_JS = """
(function () {
  try {
    var all = performance.getEntriesByType('resource');
    var res = all.slice(-400).map(function (e) {
      return {
        name: String(e.name || '').substring(0, 300),
        type: e.initiatorType || '',
        ms: Math.round(e.duration || 0),
        bytes: e.transferSize || 0,
        status: e.responseStatus || 0
      };
    });
    var nav = performance.getEntriesByType('navigation')[0];
    return JSON.stringify({
      navMs: nav ? Math.round(nav.duration || 0) : 0,
      count: all.length,
      entries: res
    });
  } catch (e) { return JSON.stringify({ error: String(e) }); }
})();
"""

    // clear site storage
    const val CLEAR_STORAGE_JS = """
(function () {
  try {
    localStorage.clear();
    sessionStorage.clear();
    return JSON.stringify({ ok: true });
  } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
})();
"""

    // async view-source: fetches the current URL and pushes the text through the bridge
    const val VIEW_SOURCE_FETCH_JS = """
(function () {
  function done(t) {
    try { window.VideoSnifferDevBridge.onSource(String(t).substring(0, 250000)); } catch (e) {}
  }
  try {
    fetch(location.href, { credentials: 'include' })
      .then(function (r) { return r.text(); })
      .then(done)
      .catch(function (e) { done('FETCH-ERROR: ' + String(e) + '  (use the HTML tab instead)'); });
  } catch (e) {
    done('FETCH-ERROR: ' + String(e));
  }
})();
"""
}

/**
 * In-app developer tools panel, opened with the F12 button (same idea as F12 on a PC browser).
 *
 * Tabs:
 *  Console  - live console.log/info/warn/error + JS errors + a JavaScript evaluator
 *  Network  - every request the WebView makes (method, URL, headers) + performance timings
 *  Elements - HTML source, view-source, DOM stats, images, scripts, links, meta tags
 *  Storage  - cookies, localStorage, sessionStorage + clear actions
 *  Device   - device / WebView / user agent info
 *  Extras   - remote debugging (chrome://inspect), JS/images toggles, force zoom, desktop UA
 *
 * Built entirely with code views (no XML) to stay consistent with MainActivity's style,
 * and styled after Chrome DevTools' dark theme. The panel slides up over the page and
 * covers ~62% of it (Expand makes it full height), like a desktop DevTools split view.
 */
class DevToolsPanel(
    private val activity: Activity,
    private val container: FrameLayout,
    private val webViewProvider: () -> WebView?,
    private val pageUrlProvider: () -> String,
    private val userAgentProvider: () -> String
) {

    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_OUTPUT_CHARS = 200000
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val TAB_CONSOLE = "Console"
        private const val TAB_NETWORK = "Network"
        private const val TAB_ELEMENTS = "Elements"
        private const val TAB_STORAGE = "Storage"
        private const val TAB_DEVICE = "Device"
        private const val TAB_EXTRAS = "Extras"
    }

    // Chrome DevTools dark palette
    private val bg = Color.rgb(32, 33, 36)
    private val surface = Color.rgb(41, 42, 45)
    private val field = Color.rgb(53, 54, 58)
    private val accent = Color.rgb(138, 180, 248)
    private val textMain = Color.rgb(232, 234, 237)
    private val dim = Color.rgb(154, 160, 166)
    private val errColor = Color.rgb(242, 139, 130)
    private val warnColor = Color.rgb(253, 214, 99)
    private val okColor = Color.rgb(129, 201, 149)

    // ---- data
    private class ConsoleEntry(val time: Long, val level: String, val text: String)
    private class NetEntry(val time: Long, val method: String, val url: String, val headers: Map<String, String>?)

    private val dataLock = Any()
    private val consoleEntries = ArrayList<ConsoleEntry>()
    private val netEntries = ArrayList<NetEntry>()
    private val commandHistory = ArrayList<String>()
    private var historyPos = -1
    private var consoleFilter: String? = null
    private var lastConsoleRendered = 0
    private var lastNetRendered = 0

    // ---- developer options state (Extras tab)
    private var remoteDebug = false
    private var jsDisabled = false
    private var imagesDisabled = false
    private var forceZoom = false
    private var desktopUa = false
    private var originalUa: String? = null

    // ---- views
    private var panel: LinearLayout? = null
    private var expanded = false
    private var activeTab = TAB_CONSOLE
    private val tabButtons = LinkedHashMap<String, TextView>()
    private val tabViews = LinkedHashMap<String, View>()
    private var consoleList: LinearLayout? = null
    private var consoleScroll: ScrollView? = null
    private var consoleInput: EditText? = null
    private var netList: LinearLayout? = null
    private var netScroll: ScrollView? = null
    private var elementsOutput: TextView? = null
    private var storageSummary: TextView? = null
    private var storageOutput: TextView? = null
    private var deviceBox: LinearLayout? = null
    private var extrasBox: LinearLayout? = null
    private val uiRefreshQueued = AtomicBoolean(false)

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isOpen: Boolean
        get() = panel?.parent != null

    // ------------------------------------------------------------ public API

    fun toggle() {
        if (isOpen) close() else open()
    }

    fun open() {
        val p = panel ?: buildPanel().also { panel = it }
        if (p.parent != null) return
        if (originalUa == null) {
            originalUa = userAgentProvider().ifEmpty { DESKTOP_UA }
        }
        val h = panelHeight()
        container.addView(
            p,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                h,
                Gravity.BOTTOM
            )
        )
        p.translationY = h.toFloat()
        p.post {
            p.animate().translationY(0f).setDuration(190L).start()
        }
        updateTabButtons()
        refreshActiveTab()
    }

    fun close() {
        val p = panel ?: return
        if (p.parent == null) return
        val target = if (p.height > 0) p.height.toFloat() else panelHeight().toFloat()
        p.animate().translationY(target).setDuration(160L).withEndAction {
            try {
                container.removeView(p)
            } catch (_: Throwable) {
            }
        }.start()
    }

    /** Called from the VideoSnifferDevBridge JavascriptInterface (background thread). */
    fun onConsoleMessage(level: String, message: String) {
        storeConsole(level, message)
        if (activeTab == TAB_CONSOLE && isOpen) queueUiRefresh()
    }

    /** Called from WebViewClient.shouldInterceptRequest etc. (background thread). */
    fun onNetworkRequest(method: String, url: String, headers: Map<String, String>?) {
        if (url.isBlank()) return
        synchronized(dataLock) {
            netEntries.add(NetEntry(System.currentTimeMillis(), method, url, headers))
            if (netEntries.size > MAX_ENTRIES) netEntries.removeAt(0)
        }
        if (activeTab == TAB_NETWORK && isOpen) queueUiRefresh()
    }

    /** Called when a new page starts loading: network log resets (console is preserved). */
    fun onNewPage() {
        synchronized(dataLock) { netEntries.clear() }
        lastNetRendered = 0
        if (activeTab == TAB_NETWORK && isOpen) refreshActiveTab()
    }

    /** Called from the bridge when the async view-source fetch completes (UI thread). */
    fun onPageSource(text: String) {
        if (text.isBlank()) return
        storeConsole("log", "view-source fetched: " + text.length + " chars")
        appendConsoleNow()
        elementsOutput?.text =
            if (text.length > MAX_OUTPUT_CHARS) {
                text.substring(0, MAX_OUTPUT_CHARS) + "\n\n... [truncated]"
            } else {
                text
            }
    }

    // ------------------------------------------------------------ internals

    private fun storeConsole(level: String, message: String) {
        synchronized(dataLock) {
            consoleEntries.add(ConsoleEntry(System.currentTimeMillis(), level, message))
            if (consoleEntries.size > MAX_ENTRIES) consoleEntries.removeAt(0)
        }
    }

    private fun appendConsoleNow() {
        if (isOpen && activeTab == TAB_CONSOLE) refreshConsole()
    }

    private fun queueUiRefresh() {
        if (uiRefreshQueued.getAndSet(true)) return
        activity.runOnUiThread {
            val p = panel
            if (p == null) {
                uiRefreshQueued.set(false)
                return@runOnUiThread
            }
            p.postDelayed({
                uiRefreshQueued.set(false)
                refreshActiveTab()
            }, 200L)
        }
    }

    private fun panelHeight(): Int {
        val ch = container.height
        val base =
            if (ch > 0) ch
            else (activity.resources.displayMetrics.heightPixels * 0.70f).toInt()
        val frac = if (expanded) 1.0f else 0.62f
        return (base * frac).toInt()
    }

    private fun toggleExpand() {
        val p = panel ?: return
        expanded = !expanded
        val lp = p.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.height = panelHeight()
            p.layoutParams = lp
        }
    }

    private fun refreshActiveTab() {
        when (activeTab) {
            TAB_CONSOLE -> refreshConsole()
            TAB_NETWORK -> refreshNetwork()
            TAB_STORAGE -> refreshStorageSummary()
            TAB_DEVICE -> buildDeviceRows()
            TAB_EXTRAS -> buildExtrasRows()
        }
    }

    private fun clearActiveTab() {
        when (activeTab) {
            TAB_CONSOLE -> {
                synchronized(dataLock) { consoleEntries.clear() }
                lastConsoleRendered = 0
                consoleList?.removeAllViews()
            }
            TAB_NETWORK -> {
                synchronized(dataLock) { netEntries.clear() }
                lastNetRendered = 0
                netList?.removeAllViews()
            }
            TAB_ELEMENTS -> elementsOutput?.text = "// cleared"
            TAB_STORAGE -> storageOutput?.text = "// cleared"
            else -> toast("Nothing to clear here")
        }
    }

    // ------------------------------------------------------------ panel chrome

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(bg, 12)
        }

        // ---- toolbar
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(10), 0, dp(6), 0)
        }
        bar.addView(
            TextView(activity).apply {
                text = "DevTools"
                textSize = 13f
                setTextColor(textMain)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(toolbarAction("Expand") { toggleExpand() })
        bar.addView(toolbarAction("Clear") { clearActiveTab() })
        bar.addView(toolbarAction("Close") { close() })
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))

        // ---- tab strip
        val strip = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(surface)
        }
        val tabsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        strip.addView(tabsRow, HorizontalScrollView.LayoutParams(-2, dp(34)))
        root.addView(strip, LinearLayout.LayoutParams(-1, dp(34)))

        // ---- tab content
        val contentWrap = FrameLayout(activity)
        root.addView(contentWrap, LinearLayout.LayoutParams(-1, 0, 1f))

        val names = arrayOf(
            TAB_CONSOLE, TAB_NETWORK, TAB_ELEMENTS,
            TAB_STORAGE, TAB_DEVICE, TAB_EXTRAS
        )
        for (name in names) {
            val label = TextView(activity).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { selectTab(name) }
            }
            tabsRow.addView(label)
            tabButtons[name] = label

            val view: View = when (name) {
                TAB_CONSOLE -> buildConsoleTab()
                TAB_NETWORK -> buildNetworkTab()
                TAB_ELEMENTS -> buildElementsTab()
                TAB_STORAGE -> buildStorageTab()
                TAB_DEVICE -> buildDeviceTab()
                else -> buildExtrasTab()
            }
            view.visibility = View.GONE
            contentWrap.addView(view, FrameLayout.LayoutParams(-1, -1))
            tabViews[name] = view
        }

        activeTab = TAB_CONSOLE
        return root
    }

    private fun selectTab(name: String) {
        activeTab = name
        updateTabButtons()
        refreshActiveTab()
    }

    private fun updateTabButtons() {
        for ((name, tv) in tabButtons) {
            val selected = name == activeTab
            tv.setTextColor(if (selected) accent else dim)
            tv.setTypeface(tv.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        for ((name, view) in tabViews) {
            view.visibility = if (name == activeTab) View.VISIBLE else View.GONE
        }
    }

    private fun toolbarAction(label: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }
    }

    private fun miniAction(label: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(textMain)
            background = rounded(field, 12)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------ Console tab

    private fun buildConsoleTab(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // filter chips
        val filters = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(surface)
        }
        val fRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }
        filters.addView(fRow, HorizontalScrollView.LayoutParams(-2, -2))
        val filterNames = arrayOf("All", "log", "info", "warn", "error")
        for (n in filterNames) {
            val selected = (n == "All")
            val c = TextView(activity).apply {
                text = n
                textSize = 11f
                setTextColor(if (selected) accent else dim)
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                setOnClickListener {
                    consoleFilter = if (n == "All") null else n
                    lastConsoleRendered = 0
                    refreshConsole()
                    for (i in 0 until fRow.childCount) {
                        val tv = fRow.getChildAt(i) as TextView
                        val sel = (consoleFilter == null && tv.text == "All") ||
                            consoleFilter == tv.text.toString()
                        tv.setTextColor(if (sel) accent else dim)
                        tv.setTypeface(tv.typeface, if (sel) Typeface.BOLD else Typeface.NORMAL)
                    }
                }
            }
            fRow.addView(c)
        }
        box.addView(filters, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity).apply { setBackgroundColor(bg) }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        scroll.addView(list, ScrollView.LayoutParams(-1, -2))
        consoleList = list
        consoleScroll = scroll
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // JS input row
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val input = EditText(activity).apply {
            hint = "JavaScript: document.title"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(textMain)
            setHintTextColor(dim)
            background = rounded(field, 6)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    runConsoleCommand()
                    true
                } else {
                    false
                }
            }
        }
        consoleInput = input
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(40), 1f))
        inputRow.addView(miniAction("^") { cycleHistory() })
        inputRow.addView(miniAction("Run") { runConsoleCommand() })
        box.addView(inputRow, LinearLayout.LayoutParams(-1, -2))

        return box
    }

    private fun refreshConsole() {
        val list = consoleList ?: return
        val entries = synchronized(dataLock) { ArrayList(consoleEntries) }
        if (consoleFilter == null) {
            if (lastConsoleRendered > entries.size) {
                list.removeAllViews()
                lastConsoleRendered = 0
            }
            for (i in lastConsoleRendered until entries.size) {
                list.addView(consoleRow(entries[i]))
            }
            lastConsoleRendered = entries.size
        } else {
            list.removeAllViews()
            for (e in entries) {
                if (e.level == consoleFilter) list.addView(consoleRow(e))
            }
            lastConsoleRendered = entries.size
        }
        autoscrollConsole()
    }

    private fun consoleRow(e: ConsoleEntry): View {
        val color = when (e.level) {
            "error" -> errColor
            "warn" -> warnColor
            "info" -> accent
            "cmd" -> okColor
            "debug" -> dim
            else -> textMain
        }
        val prefix = if (e.level == "cmd") "> " else ""
        return TextView(activity).apply {
            text = "[" + timeFmt.format(Date(e.time)) + "] " + prefix + e.text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            setPadding(0, dp(3), 0, dp(3))
            setTextIsSelectable(true)
            setOnLongClickListener {
                copyText("console", e.text)
                true
            }
        }
    }

    private fun autoscrollConsole() {
        val sc = consoleScroll ?: return
        val child = sc.getChildAt(0) ?: return
        val nearBottom = sc.scrollY + sc.height >= child.height - dp(80)
        if (nearBottom) sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }

    private fun cycleHistory() {
        val hist = synchronized(dataLock) { ArrayList(commandHistory) }
        if (hist.isEmpty()) return
        historyPos = if (historyPos < 0) hist.size - 1 else (historyPos - 1 + hist.size) % hist.size
        val input = consoleInput ?: return
        input.setText(hist[historyPos])
        input.setSelection(input.text.length)
    }

    private fun runConsoleCommand() {
        val input = consoleInput ?: return
        val expr = input.text.toString().trim()
        if (expr.isEmpty()) return
        val wv = webViewProvider()
        if (wv == null) {
            toast("Open a page first - the console runs JavaScript on the loaded page")
            return
        }
        input.setText("")
        historyPos = -1
        synchronized(dataLock) {
            commandHistory.add(expr)
            if (commandHistory.size > 50) commandHistory.removeAt(0)
        }
        storeConsole("cmd", expr)
        appendConsoleNow()
        try {
            wv.evaluateJavascript(buildEvalJs(expr)) { raw -> handleEvalResult(raw) }
        } catch (t: Throwable) {
            storeConsole("error", "eval failed: " + (t.message ?: ""))
            appendConsoleNow()
        }
    }

    private fun handleEvalResult(raw: String?) {
        val decoded = decodeScalar(raw)
        if (decoded == null) {
            storeConsole("result", "undefined")
            appendConsoleNow()
            return
        }
        val obj = try {
            JSONObject(decoded)
        } catch (_: Throwable) {
            null
        }
        if (obj != null && obj.has("ok")) {
            if (obj.optBoolean("ok")) {
                storeConsole("result", formatValue(obj.opt("value")))
            } else {
                storeConsole("error", obj.optString("error", "error"))
            }
        } else {
            storeConsole("result", decoded)
        }
        appendConsoleNow()
    }

    /** Wraps the user expression so errors come back as text instead of crashing silently. */
    private fun buildEvalJs(expr: String): String {
        val esc = expr
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val tpl = """
(function () {
  try {
    var v = eval("__VS_EXPR__");
    return JSON.stringify({ ok: true, value: v }, function (k, x) {
      if (x === undefined) return 'undefined';
      if (typeof x === 'function') return '[Function ' + (x.name || 'anonymous') + ']';
      if (typeof x === 'symbol') return x.toString();
      if (x && typeof x === 'object' && x.nodeType) return '<' + String(x.nodeName || '').toLowerCase() + '>';
      return x;
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String(e && e.message || e) });
  }
})()
""".trimIndent()
        return tpl.replace("__VS_EXPR__", esc)
    }

    private fun decodeScalar(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        if (raw == "null") return null
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v == null || v == JSONObject.NULL) null else v.toString()
        } catch (_: Throwable) {
            raw
        }
    }

    private fun formatValue(v: Any?): String {
        if (v == null || v == JSONObject.NULL) return "undefined"
        return try {
            when (v) {
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> v.toString()
            }
        } catch (_: Throwable) {
            v.toString()
        }
    }

    // ------------------------------------------------------------ Network tab

    private fun buildNetworkTab(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        actions.addView(toolbarAction("Copy URLs") { copyNetworkUrls() })
        actions.addView(toolbarAction("Perf timing") { showPerfTimings() })
        actions.addView(
            TextView(activity).apply {
                text = "requests (newest last)"
                textSize = 10f
                setTextColor(dim)
                setPadding(dp(8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        box.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        header.addView(
            TextView(activity).apply {
                text = "METHOD"
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(dim)
            },
            LinearLayout.LayoutParams(dp(48), -2)
        )
        header.addView(
            TextView(activity).apply {
                text = "URL"
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(dim)
            }
        )
        box.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(8))
        }
        scroll.addView(list, ScrollView.LayoutParams(-1, -2))
        netList = list
        netScroll = scroll
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return box
    }

    private fun refreshNetwork() {
        val list = netList ?: return
        val entries = synchronized(dataLock) { ArrayList(netEntries) }
        if (lastNetRendered > entries.size) {
            list.removeAllViews()
            lastNetRendered = 0
        }
        for (i in lastNetRendered until entries.size) {
            list.addView(netRow(entries[i]))
        }
        lastNetRendered = entries.size
        val sc = netScroll ?: return
        val child = sc.getChildAt(0)
        val nearBottom = child == null || sc.scrollY + sc.height >= child.height - dp(80)
        if (nearBottom) sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }

    private fun netRow(e: NetEntry): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { showNetDetails(e) }
        }

        val line1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        line1.addView(
            TextView(activity).apply {
                text = e.method
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(methodColor(e.method))
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(dp(48), -2)
        )
        line1.addView(
            TextView(activity).apply {
                text = hostOf(e.url)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(textMain)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row.addView(line1, LinearLayout.LayoutParams(-1, -2))

        row.addView(
            TextView(activity).apply {
                text = timeFmt.format(Date(e.time)) + "  " + guessType(e.url) + "  " + e.url
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(dim)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        return row
    }

    private fun showNetDetails(e: NetEntry) {
        val sb = StringBuilder()
        sb.append("TIME: ").append(timeFmt.format(Date(e.time))).append('\n')
        sb.append("METHOD: ").append(e.method).append('\n')
        sb.append("TYPE: ").append(guessType(e.url)).append('\n')
        sb.append("URL: ").append(e.url).append('\n')
        val hs = e.headers
        if (hs != null && hs.isNotEmpty()) {
            sb.append("\nREQUEST HEADERS:\n")
            for ((k, v) in hs) {
                sb.append("  ").append(k).append(": ").append(v).append('\n')
            }
        } else {
            sb.append("\n(headers not available for this request)\n")
        }
        AlertDialog.Builder(activity)
            .setTitle(e.method + " " + guessType(e.url))
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy URL") { _, _ -> copyText("url", e.url) }
            .show()
    }

    private fun copyNetworkUrls() {
        val urls = synchronized(dataLock) { netEntries.map { it.url } }
        if (urls.isEmpty()) {
            toast("Network list is empty")
            return
        }
        copyText("urls", urls.joinToString("\n"))
        toast("Copied " + urls.size + " URLs")
    }

    private fun showPerfTimings() {
        val wv = webViewProvider()
        if (wv == null) {
            toast("Open a page first")
            return
        }
        try {
            wv.evaluateJavascript(DevToolsSnippets.PERF_JS) { raw ->
                var title = "Performance timings"
                var body: String = decodeScalar(raw) ?: "(no data)"
                try {
                    val obj = JSONObject(decodeScalar(raw) ?: "")
                    if (obj.has("error")) {
                        body = "Error: " + obj.optString("error")
                    } else {
                        val sb = StringBuilder()
                        sb.append("Navigation: ").append(obj.optInt("navMs")).append(" ms\n")
                        sb.append("Resources: ").append(obj.optInt("count")).append("\n\n")
                        val arr = obj.optJSONArray("entries") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            sb.append(o.optString("type")).append("  ")
                                .append(o.optInt("ms")).append(" ms  ")
                                .append(o.optInt("bytes")).append(" B  ")
                                .append("status ").append(o.optInt("status"))
                                .append("\n  ")
                                .append(o.optString("name"))
                                .append("\n\n")
                        }
                        body = sb.toString()
                        title = "Performance timings (" + obj.optInt("count") + " resources)"
                    }
                } catch (_: Throwable) {
                }
                showTextDialog(title, body)
            }
        } catch (t: Throwable) {
            toast("JS error: " + (t.message ?: ""))
        }
    }

    // ------------------------------------------------------------ Elements tab

    private fun buildElementsTab(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val chips = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(surface)
        }
        val cRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        chips.addView(cRow, HorizontalScrollView.LayoutParams(-2, -2))
        val items = arrayOf("HTML", "View Source", "Stats", "Images", "Scripts", "Links", "Meta")
        for (n in items) {
            cRow.addView(chip(n) { loadElementInfo(n) })
        }
        box.addView(chips, LinearLayout.LayoutParams(-1, -2))

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        actions.addView(toolbarAction("Copy output") {
            val t = elementsOutput?.text?.toString().orEmpty()
            if (t.isBlank()) toast("Nothing to copy") else copyText("elements", t)
        })
        box.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity)
        val out = TextView(activity).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(textMain)
            setPadding(dp(8), dp(6), dp(8), dp(10))
            setTextIsSelectable(true)
            text = "Tap HTML / View Source / Stats / Images / Scripts / Links / Meta to inspect the loaded page."
        }
        elementsOutput = out
        scroll.addView(out, ScrollView.LayoutParams(-1, -2))
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return box
    }

    private fun setElementsOutput(text: String) {
        elementsOutput?.text = text
    }

    private fun loadElementInfo(which: String) {
        val wv = webViewProvider()
        if (wv == null) {
            toast("Open a page first")
            return
        }
        when (which) {
            "HTML" -> runJs("document.documentElement.outerHTML") { raw ->
                val html = decodeScalar(raw) ?: "(empty)"
                setElementsOutput(
                    if (html.length > MAX_OUTPUT_CHARS) {
                        html.substring(0, MAX_OUTPUT_CHARS) +
                            "\n\n... [truncated " + (html.length - MAX_OUTPUT_CHARS) + " chars]"
                    } else {
                        html
                    }
                )
            }

            "View Source" -> runJs(DevToolsSnippets.VIEW_SOURCE_FETCH_JS) { _ ->
                // result arrives asynchronously through the bridge (onPageSource)
                toast("Fetching source...")
            }

            "Stats" -> runJs(DevToolsSnippets.STATS_JS) { raw ->
                setElementsOutput(prettyJson(raw) ?: "(no data)")
            }

            "Images" -> runJs(DevToolsSnippets.IMAGES_JS) { raw ->
                setElementsOutput(
                    formatItemList(raw, "images") { i, o ->
                        (i + 1).toString() + ". " + o.optString("src") +
                            "  (" + o.optInt("w") + "x" + o.optInt("h") + ")" +
                            (if (o.optString("alt").isNotEmpty()) "  alt=" + o.optString("alt") else "")
                    }
                )
            }

            "Scripts" -> runJs(DevToolsSnippets.SCRIPTS_JS) { raw ->
                setElementsOutput(
                    formatItemList(raw, "scripts") { i, o ->
                        (i + 1).toString() + ". " + o.optString("src") +
                            (if (o.optInt("len") > 0) "  [" + o.optInt("len") + " chars]" else "")
                    }
                )
            }

            "Links" -> runJs(DevToolsSnippets.LINKS_JS) { raw ->
                setElementsOutput(
                    formatItemList(raw, "links") { i, o ->
                        (i + 1).toString() + ". " + o.optString("text") +
                            "  ->  " + o.optString("href")
                    }
                )
            }

            "Meta" -> runJs(DevToolsSnippets.METAS_JS) { raw ->
                setElementsOutput(
                    formatItemList(raw, "meta tags") { i, o ->
                        (i + 1).toString() + ". " + o.optString("k") + " = " + o.optString("v")
                    }
                )
            }
        }
    }

    private fun runJs(js: String, cb: (String?) -> Unit) {
        val wv = webViewProvider()
        if (wv == null) {
            toast("Open a page first")
            return
        }
        try {
            wv.evaluateJavascript(js) { raw -> cb(raw) }
        } catch (t: Throwable) {
            toast("JS error: " + (t.message ?: ""))
        }
    }

    private fun formatItemList(raw: String?, label: String, line: (Int, JSONObject) -> String): String {
        val arr = try {
            JSONTokener(decodeScalar(raw) ?: "").nextValue() as? JSONArray
        } catch (_: Throwable) {
            null
        }
        if (arr == null) return "(no data)"
        if (arr.length() == 0) return "(no " + label + " on this page)"
        val sb = StringBuilder()
        sb.append(label).append(": ").append(arr.length()).append("\n\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) sb.append(line(i, o)).append('\n')
        }
        return sb.toString()
    }

    private fun prettyJson(raw: String?): String? {
        val decoded = decodeScalar(raw) ?: return null
        return try {
            val v = JSONTokener(decoded).nextValue()
            when (v) {
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> v.toString()
            }
        } catch (_: Throwable) {
            decoded
        }
    }

    // ------------------------------------------------------------ Storage tab

    private fun buildStorageTab(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val summary = TextView(activity).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(dim)
            setPadding(dp(8), dp(6), dp(8), dp(4))
            text = "Open a page to inspect its storage."
        }
        storageSummary = summary
        box.addView(summary, LinearLayout.LayoutParams(-1, -2))

        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        row1.addView(chip("Refresh") { refreshStorageSummary() })
        row1.addView(chip("Cookies") { showCookies() })
        row1.addView(chip("Local") { dumpStorageJs(DevToolsSnippets.LS_JS, "localStorage") })
        row1.addView(chip("Session") { dumpStorageJs(DevToolsSnippets.SS_JS, "sessionStorage") })
        box.addView(row1, LinearLayout.LayoutParams(-1, -2))

        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        row2.addView(chip("Clear Cookies") { clearCookies() })
        row2.addView(chip("Clear Storage") { clearSiteStorage() })
        row2.addView(chip("Clear Cache") { clearCache() })
        box.addView(row2, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity)
        val out = TextView(activity).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(textMain)
            setPadding(dp(8), dp(6), dp(8), dp(10))
            setTextIsSelectable(true)
        }
        storageOutput = out
        scroll.addView(out, ScrollView.LayoutParams(-1, -2))
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return box
    }

    private fun setStorageOutput(text: String) {
        storageOutput?.text = text
    }

    private fun refreshStorageSummary() {
        val pageUrl = pageUrlProvider().ifEmpty { "(home)" }
        val cookies = try {
            CookieManager.getInstance().getCookie(pageUrl)
        } catch (_: Throwable) {
            null
        }
        val cookieCount = if (cookies.isNullOrEmpty()) 0 else cookies.split(";").size
        val wv = webViewProvider()
        if (wv == null) {
            storageSummary?.text = "No page loaded.\nCookies: " + cookieCount
            return
        }
        storageSummary?.text =
            "Page: " + pageUrl + "\nCookies: " + cookieCount + "  |  counting storage..."
        try {
            wv.evaluateJavascript(DevToolsSnippets.STORAGE_COUNT_JS) { raw ->
                var ls = "n/a"
                var ss = "n/a"
                try {
                    val obj = JSONObject(decodeScalar(raw) ?: "")
                    val l = obj.optInt("ls", -1)
                    val s = obj.optInt("ss", -1)
                    ls = if (l >= 0) l.toString() + " keys" else "n/a"
                    ss = if (s >= 0) s.toString() + " keys" else "n/a"
                } catch (_: Throwable) {
                }
                storageSummary?.text =
                    "Page: " + pageUrl + "\nCookies: " + cookieCount +
                        "  |  LocalStorage: " + ls + "  |  SessionStorage: " + ss
            }
        } catch (_: Throwable) {
        }
    }

    private fun showCookies() {
        val pageUrl = pageUrlProvider()
        if (pageUrl.isBlank()) {
            toast("Open a page first")
            return
        }
        val cookies = try {
            CookieManager.getInstance().getCookie(pageUrl)
        } catch (_: Throwable) {
            null
        }
        if (cookies.isNullOrEmpty()) {
            setStorageOutput("(no cookies for " + pageUrl + ")")
            return
        }
        val sb = StringBuilder()
        for (c in cookies.split(";")) {
            sb.append(c.trim()).append('\n')
        }
        setStorageOutput("Cookies for " + pageUrl + ":\n\n" + sb.toString())
    }

    private fun dumpStorageJs(js: String, label: String) {
        runJs(js) { raw ->
            var text = "(no data)"
            try {
                val obj = JSONObject(decodeScalar(raw) ?: "")
                if (obj.has("error")) {
                    text = "Error: " + obj.optString("error")
                } else {
                    val arr = obj.optJSONArray("items") ?: JSONArray()
                    val sb = StringBuilder()
                    sb.append(label).append(": ").append(obj.optInt("count")).append(" keys\n\n")
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        sb.append(o.optString("k")).append(" = ")
                            .append(o.optString("v")).append('\n')
                    }
                    text = sb.toString()
                }
            } catch (_: Throwable) {
            }
            setStorageOutput(text)
        }
    }

    private fun clearCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            toast("All cookies cleared")
        } catch (t: Throwable) {
            toast("Failed: " + (t.message ?: ""))
        }
        refreshStorageSummary()
    }

    private fun clearSiteStorage() {
        runJs(DevToolsSnippets.CLEAR_STORAGE_JS) { raw ->
            try {
                val obj = JSONObject(decodeScalar(raw) ?: "")
                if (obj.optBoolean("ok")) toast("localStorage + sessionStorage cleared")
                else toast("Failed: " + obj.optString("error"))
            } catch (_: Throwable) {
            }
        }
        refreshStorageSummary()
    }

    private fun clearCache() {
        val wv = webViewProvider()
        if (wv == null) {
            toast("Open a page first")
            return
        }
        try {
            wv.clearCache(true)
            toast("Cache cleared")
        } catch (t: Throwable) {
            toast("Failed: " + (t.message ?: ""))
        }
    }

    // ------------------------------------------------------------ Device tab

    private fun buildDeviceTab(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }
        actions.addView(chip("Copy UA") { copyText("user-agent", userAgentProvider()) })
        box.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity)
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        deviceBox = rows
        scroll.addView(rows, ScrollView.LayoutParams(-1, -2))
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return box
    }

    private fun buildDeviceRows() {
        val box = deviceBox ?: return
        box.removeAllViews()

        val dm = activity.resources.displayMetrics
        val wvPkg = try {
            WebViewCompat.getCurrentWebViewPackage(activity)
        } catch (_: Throwable) {
            null
        }
        val wvInfo =
            if (wvPkg != null) wvPkg.packageName + "  v" + wvPkg.versionName
            else "unknown"
        val appVer = try {
            val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pi.versionName ?: "1.0"
        } catch (_: Throwable) {
            "?"
        }

        addDevRow(box, "App", "VideoSniffer " + appVer)
        addDevRow(box, "Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        addDevRow(box, "Device", Build.MANUFACTURER + " " + Build.MODEL)
        addDevRow(
            box, "Screen",
            dm.widthPixels + " x " + dm.heightPixels + " px  @ " +
                dm.densityDpi + " dpi (density " + dm.density + ")"
        )
        addDevRow(box, "WebView", wvInfo)
        addDevRow(box, "Page", pageUrlProvider().ifEmpty { "(home)" })
        addDevRow(box, "User agent", userAgentProvider())
    }

    private fun addDevRow(box: LinearLayout, label: String, value: String) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        row.addView(
            TextView(activity).apply {
                text = label
                textSize = 10f
                setTextColor(accent)
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        row.addView(
            TextView(activity).apply {
                text = value
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(textMain)
                setTextIsSelectable(true)
            }
        )
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    // ------------------------------------------------------------ Extras tab

    private fun buildExtrasTab(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity)
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(12))
        }
        extrasBox = rows
        scroll.addView(rows, ScrollView.LayoutParams(-1, -2))
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return box
    }

    private fun buildExtrasRows() {
        val box = extrasBox ?: return
        box.removeAllViews()

        box.addView(extrasTitle("Remote debugging"))
        box.addView(
            extrasToggle(
                "Allow chrome://inspect on a PC (USB debugging)",
                remoteDebug
            ) { on ->
                remoteDebug = on
                try {
                    WebView.setWebContentsDebuggingEnabled(on)
                    toast(if (on) "Remote debugging ON - connect USB and open chrome://inspect" else "Remote debugging OFF")
                } catch (_: Throwable) {
                }
            }
        )
        box.addView(
            extrasNote(
                "USB se phone connect karke PC ke Chrome me chrome://inspect/#devices kholo. " +
                    "Wahan full desktop DevTools (Sources, Performance, Lighthouse) milega."
            )
        )

        box.addView(extrasTitle("Page toggles (page reloads on change)"))
        box.addView(
            extrasToggle("JavaScript enabled", !jsDisabled) { on ->
                jsDisabled = !on
                val wv = webViewProvider()
                if (wv != null) {
                    try {
                        wv.settings.javaScriptEnabled = on
                        wv.reload()
                    } catch (_: Throwable) {
                    }
                }
            }
        )
        box.addView(
            extrasToggle("Load images", !imagesDisabled) { on ->
                imagesDisabled = !on
                val wv = webViewProvider()
                if (wv != null) {
                    try {
                        wv.settings.loadsImagesAutomatically = on
                        wv.reload()
                    } catch (_: Throwable) {
                    }
                }
            }
        )
        box.addView(
            extrasToggle("Force pinch zoom", forceZoom) { on ->
                forceZoom = on
                val wv = webViewProvider()
                if (wv != null) {
                    try {
                        wv.settings.setSupportZoom(on)
                        wv.settings.builtInZoomControls = on
                        wv.settings.displayZoomControls = false
                    } catch (_: Throwable) {
                    }
                }
            }
        )
        box.addView(
            extrasToggle("Desktop user agent", desktopUa) { on ->
                desktopUa = on
                val wv = webViewProvider()
                if (wv != null) {
                    try {
                        wv.settings.userAgentString =
                            if (on) DESKTOP_UA else (originalUa ?: userAgentProvider())
                        wv.reload()
                    } catch (_: Throwable) {
                    }
                }
            }
        )
    }

    private fun extrasTitle(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        }
    }

    private fun extrasNote(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 10f
            setTextColor(dim)
            setPadding(0, dp(2), 0, dp(4))
        }
    }

    private fun extrasToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(
            TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(textMain)
                setPadding(0, 0, dp(8), 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val sw = Switch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, checkedNow -> onChange(checkedNow) }
        }
        row.addView(sw, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    // ------------------------------------------------------------ helpers

    private fun showTextDialog(title: String, text: String) {
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(textMain)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
        }
        val sc = ScrollView(activity).apply { addView(tv) }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ -> copyText(title, text) }
            .show()
    }

    private fun copyText(label: String, text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            toast("Copied: " + label)
        } catch (_: Throwable) {
            toast("Copy failed")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun methodColor(method: String): Int {
        return when (method.uppercase(Locale.US)) {
            "GET" -> okColor
            "POST" -> warnColor
            "PUT", "PATCH" -> accent
            "DELETE" -> errColor
            else -> dim
        }
    }

    private fun hostOf(url: String): String {
        return try {
            val u = java.net.URI(url)
            val host = u.host ?: url
            val path = u.path ?: ""
            if (path.length > 24) host + path.substring(0, 24) + "..." else host + path
        } catch (_: Throwable) {
            url
        }
    }

    private fun guessType(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        val seg = clean.substringAfterLast('/')
        val ext = if (seg.contains('.')) seg.substringAfterLast('.').lowercase(Locale.US) else ""
        return when (ext) {
            "m3u8" -> "HLS"
            "mpd" -> "DASH"
            "mp4", "webm", "mkv", "avi", "mov", "3gp", "flv" -> "VIDEO"
            "m4s", "ts" -> "SEGMENT"
            "m4a", "mp3", "aac", "ogg", "wav", "opus" -> "AUDIO"
            "jpg", "jpeg", "png", "gif", "webp", "svg", "ico" -> "IMAGE"
            "js" -> "JS"
            "css" -> "CSS"
            "html", "htm" -> "DOC"
            "json" -> "JSON"
            "woff", "woff2", "ttf", "otf" -> "FONT"
            else -> "RES"
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun roundedTop(color: Int, radiusDp: Int): GradientDrawable {
        val r = dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }
}
