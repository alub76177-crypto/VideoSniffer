package com.videosniffer.browser

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects media candidates per tab.
 *
 * Inputs:
 *  - [onRequest]: called from WebViewClient.shouldInterceptRequest (a background thread).
 *  - [onVideoElementReport]: called from the page observer bridge (a background thread).
 *
 * Limits (also shown to the user): WebView does not expose response headers or bodies for
 * normal requests, and not every request reaches shouldInterceptRequest. Detection is
 * therefore best-effort. An optional cookie-less HEAD probe can verify a MIME type; it
 * cannot verify resources that need the browser session.
 *
 * Listener callbacks are always delivered on the main thread.
 */
class VideoDetectionManager(private val listener: Listener) {

    interface Listener {
        fun onMediaDetected(media: DetectedMedia)
        fun onMediaListChanged(tabId: Long)
    }

    companion object {
        const val MAX_ITEMS_PER_TAB = 150
        const val MAX_URL_LENGTH = 4096
        private const val PROBE_TIMEOUT_MS = 6000
        private val PROBE_TYPES = setOf(
            MediaType.DIRECT_VIDEO, MediaType.HLS_MANIFEST, MediaType.DASH_MANIFEST
        )
    }

    private val lock = Any()
    private val store = HashMap<Long, LinkedHashMap<String, DetectedMedia>>()
    private val unseen = HashMap<Long, Int>()
    private val idGen = AtomicLong(1)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** When false, nothing is ever requested by the app itself; only observed data is used. */
    @Volatile
    var probeEnabled: Boolean = true

    private val probeExecutor = ThreadPoolExecutor(
        1, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(24),
        ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    // ------------------------------------------------------------------ inputs

    fun onRequest(
        tabId: Long,
        rawUrl: String,
        method: String,
        headers: Map<String, String>?,
        pageUrl: String?,
        pageTitle: String?,
        userAgent: String?
    ) {
        val isGet = method.equals("GET", ignoreCase = true)
        if (!isGet && !MediaUrlClassifier.isPostMediaHint(rawUrl)) return
        if (!isHttp(rawUrl) || rawUrl.length > MAX_URL_LENGTH) return

        // Tracking wrapper (e.g. ...?vurl=<real .m3u8>) -> record the real media link instead.
        val url = MediaUrlClassifier.unwrapEmbeddedMediaUrl(rawUrl)
        if (MediaUrlClassifier.isKeyFile(url)) return
        val wrapped = url != rawUrl

        var result = MediaUrlClassifier.classify(url)
        if (!isGet) {
            result = result.copy(
                reason = result.reason + " Request used $method (protected/SABR-style stream); it may not be directly downloadable."
            )
        }
        val hasRange = !wrapped &&
            headers?.keys?.any { it.equals("Range", ignoreCase = true) } == true
        val hasAuthHeader = !wrapped &&
            headers?.keys?.any { it.equals("Authorization", ignoreCase = true) } == true

        if (hasRange) {
            result = when (result.type) {
                MediaType.UNKNOWN -> MediaUrlClassifier.Result(
                    MediaType.DIRECT_VIDEO, Confidence.LOW,
                    "The browser sent a Range request, which is typical of media playback, but the format is unknown."
                )
                MediaType.DIRECT_VIDEO ->
                    if (result.confidence == Confidence.LOW) MediaUrlClassifier.Result(
                        result.type, Confidence.MEDIUM,
                        result.reason + " The browser also sent a Range request (typical of media playback)."
                    ) else result
                else -> result
            }
        }

        if (result.type == MediaType.UNKNOWN || result.type == MediaType.THUMBNAIL) return

        record(
            tabId, url, result, DetectionMethod.REQUEST_OBSERVED, DetectionStatus.CANDIDATE,
            null, pageUrl, pageTitle,
            hasAuthHeader || !isGet || MediaUrlClassifier.looksAuthProtected(url), userAgent
        )
    }

    /** Report from the injected JS (fetch/XHR responses, text scan, video elements, iframes). */
    fun onPageMediaReport(
        tabId: Long,
        rawUrl: String,
        mime: String?,
        playing: Boolean,
        source: String?,
        pageUrl: String?,
        pageTitle: String?,
        userAgent: String?
    ) {
        if (!isHttp(rawUrl) || rawUrl.length > MAX_URL_LENGTH) return
        val url = MediaUrlClassifier.unwrapEmbeddedMediaUrl(rawUrl)
        if (MediaUrlClassifier.isKeyFile(url)) return

        // "fetch-master" / "xhr-master": the page's JS read the playlist body and it lists variants.
        val isMaster = source?.endsWith("-master") == true

        val fromVideo = source?.startsWith("video") == true
        var result = MediaUrlClassifier.classify(url, mime, fromVideo)
        if (isMaster) {
            result = MediaUrlClassifier.Result(
                MediaType.HLS_MANIFEST, Confidence.HIGH,
                "Master playlist: its body lists several quality variants."
            )
        } else if (result.type == MediaType.THUMBNAIL || result.type == MediaType.UNKNOWN) {
            return
        }

        val nm = MediaUrlClassifier.normalizeMime(mime)
        val verified = nm != null && result.confidence == Confidence.HIGH
        val status =
            if (playing || verified || isMaster) DetectionStatus.CONFIRMED else DetectionStatus.CANDIDATE
        result = result.copy(reason = result.reason + " Reported by the page's JavaScript (${source ?: "page"}).")

        record(
            tabId, url, result, DetectionMethod.PAGE_OBSERVER, status,
            nm, pageUrl, pageTitle, MediaUrlClassifier.looksAuthProtected(url), userAgent,
            isMaster = isMaster
        )
    }

    /** Report from the page observer: the currentSrc/source of an HTML5 <video> element. */
    fun onVideoElementReport(
        tabId: Long,
        rawSrc: String,
        playing: Boolean,
        pageUrl: String?,
        pageTitle: String?,
        userAgent: String?
    ) {
        if (!isHttp(rawSrc) || rawSrc.length > MAX_URL_LENGTH) return
        val src = MediaUrlClassifier.unwrapEmbeddedMediaUrl(rawSrc)
        if (MediaUrlClassifier.isKeyFile(src)) return

        var result = MediaUrlClassifier.classify(src, null, true)
        if (result.type == MediaType.THUMBNAIL) return
        if (playing) {
            result = MediaUrlClassifier.Result(
                result.type, result.confidence,
                result.reason + " The element reported it was playing this URL."
            )
        }
        record(
            tabId, src, result, DetectionMethod.PAGE_OBSERVER,
            if (playing) DetectionStatus.CONFIRMED else DetectionStatus.CANDIDATE,
            null, pageUrl, pageTitle, MediaUrlClassifier.looksAuthProtected(src), userAgent
        )
    }

    // ------------------------------------------------------------------ queries

    fun getItems(tabId: Long): List<DetectedMedia> = synchronized(lock) {
        store[tabId]?.values?.reversed() ?: emptyList()
    }

    fun unseenCount(tabId: Long): Int = synchronized(lock) { unseen[tabId] ?: 0 }

    fun markSeen(tabId: Long) {
        synchronized(lock) { unseen[tabId] = 0 }
        mainHandler.post { listener.onMediaListChanged(tabId) }
    }

    // ------------------------------------------------------------------ mutations

    fun remove(tabId: Long, mediaId: Long) {
        synchronized(lock) {
            val map = store[tabId] ?: return
            val entry = map.entries.firstOrNull { it.value.id == mediaId } ?: return
            map.remove(entry.key)
        }
        mainHandler.post { listener.onMediaListChanged(tabId) }
    }

    fun clear(tabId: Long) {
        synchronized(lock) {
            store[tabId]?.clear()
            unseen[tabId] = 0
        }
        mainHandler.post { listener.onMediaListChanged(tabId) }
    }

    /** Call when a tab closes. Pending probe results for it are discarded automatically. */
    fun clearTab(tabId: Long) {
        synchronized(lock) {
            store.remove(tabId)
            unseen.remove(tabId)
        }
    }

    /** Call from onTrimMemory: keep only the newest entries per tab. */
    fun trimMemory(keepPerTab: Int = 30) {
        synchronized(lock) {
            for (map in store.values) {
                while (map.size > keepPerTab) {
                    val it = map.entries.iterator()
                    it.next()
                    it.remove()
                }
            }
        }
    }

    fun shutdown() {
        probeExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------ internals

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun record(
        tabId: Long,
        url: String,
        result: MediaUrlClassifier.Result,
        method: DetectionMethod,
        status: DetectionStatus,
        mimeType: String?,
        pageUrl: String?,
        pageTitle: String?,
        authHint: Boolean,
        userAgent: String?,
        isMaster: Boolean = false
    ) {
        val type = result.type
        val effectiveStatus =
            if (type == MediaType.AUDIO || type == MediaType.SUBTITLE) DetectionStatus.RELATED else status
        val key = MediaUrlClassifier.dedupKey(url, type)
        var created: DetectedMedia? = null
        var upgraded = false

        synchronized(lock) {
            val map = store.getOrPut(tabId) { LinkedHashMap() }
            val existing = map[key]
            if (existing == null) {
                val item = DetectedMedia(
                    id = idGen.getAndIncrement(),
                    tabId = tabId,
                    url = MediaUrlClassifier.canonicalUrl(url),
                    host = MediaUrlClassifier.hostOf(url),
                    type = type,
                    confidence = if (effectiveStatus == DetectionStatus.CONFIRMED) Confidence.HIGH else result.confidence,
                    status = effectiveStatus,
                    method = method,
                    reason = result.reason,
                    mimeType = mimeType,
                    pageUrl = pageUrl ?: "",
                    pageTitle = pageTitle?.takeIf { it.isNotBlank() } ?: "Untitled page",
                    detectedAtMillis = System.currentTimeMillis(),
                    mayRequireAuth = authHint,
                    isMaster = isMaster
                )
                map[key] = item
                unseen[tabId] = (unseen[tabId] ?: 0) + 1
                while (map.size > MAX_ITEMS_PER_TAB) {
                    val it = map.entries.iterator()
                    it.next()
                    it.remove()
                }
                created = item
            } else {
                var updated = existing
                if (effectiveStatus == DetectionStatus.CONFIRMED &&
                    existing.status != DetectionStatus.CONFIRMED
                ) {
                    updated = updated.copy(
                        status = DetectionStatus.CONFIRMED,
                        confidence = Confidence.HIGH,
                        reason = result.reason,
                        method = method
                    )
                }
                if (isMaster && !updated.isMaster) {
                    updated = updated.copy(isMaster = true)
                }
                if (updated != existing) {
                    map[key] = updated
                    upgraded = true
                }
            }
        }

        val newItem = created
        if (newItem != null) {
            mainHandler.post { listener.onMediaDetected(newItem) }
            if (probeEnabled && newItem.status == DetectionStatus.CANDIDATE && newItem.type in PROBE_TYPES) {
                scheduleProbe(newItem, key, userAgent)
            }
        } else if (upgraded) {
            mainHandler.post { listener.onMediaListChanged(tabId) }
        }
    }

    private fun updateItem(tabId: Long, key: String, transform: (DetectedMedia) -> DetectedMedia) {
        var changed = false
        synchronized(lock) {
            val map = store[tabId] ?: return
            val current = map[key] ?: return
            map[key] = transform(current)
            changed = true
        }
        if (changed) mainHandler.post { listener.onMediaListChanged(tabId) }
    }

    private fun scheduleProbe(media: DetectedMedia, key: String, userAgent: String?) {
        try {
            probeExecutor.execute { runProbe(media, key, userAgent) }
        } catch (e: RejectedExecutionException) {
            // Shutting down: ignore.
        }
    }

    /**
     * Cookie-less HEAD request (GET with Range 0-0 as fallback). No cookies, no credentials.
     * A failure only means "could not verify"; the item stays a Candidate.
     */
    private fun runProbe(media: DetectedMedia, key: String, userAgent: String?) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(media.url, "HEAD", userAgent, media.pageUrl)
            var code = conn.responseCode
            if (code == 405 || code == 501 || code == 400) {
                conn.disconnect()
                conn = open(media.url, "GET", userAgent, media.pageUrl)
                conn.setRequestProperty("Range", "bytes=0-0")
                code = conn.responseCode
            }
            applyProbeResult(media, key, code, conn.contentType)
        } catch (e: Exception) {
            // Network error or timeout: leave as Candidate.
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String, method: String, userAgent: String?, referer: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = PROBE_TIMEOUT_MS
        c.readTimeout = PROBE_TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.useCaches = false
        if (!userAgent.isNullOrBlank()) c.setRequestProperty("User-Agent", userAgent)
        if (!referer.isNullOrBlank() && isHttp(referer)) c.setRequestProperty("Referer", referer)
        return c
    }

    private fun applyProbeResult(media: DetectedMedia, key: String, code: Int, rawMime: String?) {
        val mime = MediaUrlClassifier.normalizeMime(rawMime)
        val ok = code in 200..299
        val authIssue = code == 401 || code == 403

        updateItem(media.tabId, key) { cur ->
            when {
                authIssue -> cur.copy(
                    mayRequireAuth = true,
                    reason = cur.reason + " A cookie-less check was refused (HTTP $code), so a session or authorization is likely required."
                )
                ok && mime != null -> {
                    val re = MediaUrlClassifier.classify(cur.url, mime)
                    when (re.type) {
                        MediaType.DIRECT_VIDEO, MediaType.HLS_MANIFEST,
                        MediaType.DASH_MANIFEST, MediaType.MEDIA_SEGMENT -> cur.copy(
                            type = re.type,
                            confidence = Confidence.HIGH,
                            status = DetectionStatus.CONFIRMED,
                            mimeType = mime,
                            reason = re.reason + " Verified by a cookie-less HEAD check."
                        )
                        MediaType.AUDIO -> cur.copy(
                            type = MediaType.AUDIO,
                            confidence = Confidence.HIGH,
                            status = DetectionStatus.RELATED,
                            mimeType = mime,
                            reason = re.reason + " Verified by a cookie-less HEAD check."
                        )
                        else -> cur.copy(
                            confidence = Confidence.LOW,
                            mimeType = mime,
                            reason = cur.reason + " A cookie-less check returned $mime, not media; it may need the page session."
                        )
                    }
                }
                else -> cur
            }
        }
    }
}
