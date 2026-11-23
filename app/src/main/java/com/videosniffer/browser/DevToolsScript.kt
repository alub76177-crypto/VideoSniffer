package com.videosniffer.browser

/**
 * F12 DevTools engine (PC-browser style) built on Eruda.
 *
 * Eruda gives the same panels as Chrome's F12 DevTools:
 *   - Console  : logs, errors, warnings + live JS execution
 *   - Elements : DOM tree, CSS inspection, element picker
 *   - Network  : XHR/fetch requests, headers and response bodies
 *   - Resources: localStorage, sessionStorage, cookies, scripts, images
 *   - Info     : URL, user agent, device info, page performance
 *
 * The Eruda bundle (eruda.min.js) is downloaded once from a CDN, cached in
 * the app's cacheDir and reused from there - so after the first successful
 * load DevTools works fully offline. If the download fails, CDN_LOADER_JS
 * is the last-resort in-page script loader.
 */
object DevToolsScript {

    /** Process-level cache of the downloaded Eruda source (avoids re-reading disk). */
    @Volatile
    var cachedSource: String? = null

    /** Sanity check so we never evaluate a truncated file or an HTML error page. */
    const val MIN_SOURCE_LENGTH = 50_000

    /** Eruda download sources (tried in order). */
    val CDN_URLS = listOf(
        "https://cdn.jsdelivr.net/npm/eruda@3.4.3/eruda.min.js",
        "https://cdn.jsdelivr.net/npm/eruda@3/eruda.min.js",
        "https://unpkg.com/eruda@3.4.3/eruda.min.js"
    )

    /** Install + init Eruda. Panel stays hidden (entry button visible). */
    val INIT_JS = """
        (function () {
            try {
                if (!window.eruda) return;
                if (window.__vsDevToolsInit) return;
                try {
                    eruda.init({
                        tool: ['console', 'elements', 'network', 'resources', 'info'],
                        autoScale: true
                    });
                } catch (e) {
                    eruda.init();
                }
                window.__vsDevToolsInit = true;
                window.__vsDevToolsVisible = false;
            } catch (e) {}
        })();
    """.trimIndent()

    /** Install + init Eruda and immediately open the panel (first F12 press). */
    val INIT_AND_SHOW_JS = """
        (function () {
            try {
                if (!window.eruda) return;
                if (!window.__vsDevToolsInit) {
                    try {
                        eruda.init({
                            tool: ['console', 'elements', 'network', 'resources', 'info'],
                            autoScale: true
                        });
                    } catch (e) {
                        eruda.init();
                    }
                    window.__vsDevToolsInit = true;
                }
                window.__vsDevToolsVisible = true;
                eruda.show();
            } catch (e) {}
        })();
    """.trimIndent()

    /** Toggle the panel open/closed without reloading (F12 behaviour on PC). */
    val TOGGLE_JS = """
        (function () {
            try {
                if (!window.eruda || !window.__vsDevToolsInit) return;
                if (window.__vsDevToolsVisible) {
                    eruda.hide();
                    window.__vsDevToolsVisible = false;
                } else {
                    eruda.show();
                    window.__vsDevToolsVisible = true;
                }
            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Last-resort in-page loader: appends a <script> tag pointing at a CDN.
     * Used only when the Kotlin-side download failed (e.g. proxy blocks the
     * app but the WebView still has network). Falls back across 3 CDNs.
     */
    val CDN_LOADER_JS = """
        (function () {
            try {
                if (window.__vsDevToolsInit) {
                    eruda.show();
                    return;
                }
                var urls = [
                    'https://cdn.jsdelivr.net/npm/eruda@3.4.3/eruda.min.js',
                    'https://cdn.jsdelivr.net/npm/eruda@3/eruda.min.js',
                    'https://unpkg.com/eruda@3.4.3/eruda.min.js'
                ];
                var i = 0;
                (function next() {
                    if (i >= urls.length) return;
                    var s = document.createElement('script');
                    s.src = urls[i++];
                    s.onload = function () {
                        try {
                            if (window.eruda && !window.__vsDevToolsInit) {
                                try {
                                    eruda.init({
                                        tool: ['console', 'elements', 'network', 'resources', 'info'],
                                        autoScale: true
                                    });
                                } catch (e) {
                                    eruda.init();
                                }
                                window.__vsDevToolsInit = true;
                            }
                            if (window.__vsDevToolsInit) {
                                window.__vsDevToolsVisible = true;
                                eruda.show();
                            }
                        } catch (e) {}
                    };
                    s.onerror = next;
                    (document.head || document.documentElement).appendChild(s);
                })();
            } catch (e) {}
        })();
    """.trimIndent()
}
