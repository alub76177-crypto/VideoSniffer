package com.videosniffer.browser

import android.net.Uri
import java.util.Locale

/**
 * Classifies a URL (and optionally a MIME type) using several signals and explains why.
 * Query parameters are never modified, because signed CDN URLs depend on them.
 */
object MediaUrlClassifier {

    data class Result(val type: MediaType, val confidence: Confidence, val reason: String)

    private val VIDEO_EXT = setOf("mp4", "webm", "3gp", "3g2", "mov", "m4v", "mkv", "ogv")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")
    private val SUBTITLE_EXT = setOf("vtt", "srt", "ttml", "dfxp", "ass", "ssa")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "bmp", "avif")

    private val SEGMENT_NAME = Regex(
        "(segment|chunk|fragment|frag[-_]?\\d|seg[-_]?\\d|(^|[-_])init([-_.]|$))"
    )
    private val MIME_QUERY_HINT = Regex("(mime|mime_type|mimetype|content[-_]?type)=video")
    private val MEDIA_IN_QUERY = Regex("\\.(mp4|webm|mkv|m4v|mov)(?![a-z0-9])")
    private val VOLATILE_PARAMS = setOf("range", "rn", "rbuf")

    private val AUTH_PARAMS = setOf(
        "token", "access_token", "auth", "authorization", "sig", "signature", "expires",
        "expire", "policy", "key-pair-id", "hdnts", "hdntl", "hmac",
        "x-amz-signature", "x-amz-credential", "x-goog-signature"
    )

    fun classify(url: String, mimeType: String? = null, fromVideoElement: Boolean = false): Result {
        val mime = normalizeMime(mimeType)
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = (uri?.path ?: "").lowercase(Locale.ROOT)
        val fileName = path.substringAfterLast('/')
        val ext = if (fileName.contains('.')) fileName.substringAfterLast('.') else ""
        val query = (uri?.encodedQuery ?: "").lowercase(Locale.ROOT)
        val segmentLike = SEGMENT_NAME.containsMatchIn(fileName)

        // 1) MIME type is the strongest signal.
        if (mime != null) {
            classifyMime(mime, segmentLike)?.let { return boost(it, fromVideoElement) }
        }

        // 2) File extension (path only; query and fragment are ignored).
        val byExt: Result? = when {
            ext == "m3u8" || ext == "m3u" -> Result(
                MediaType.HLS_MANIFEST, Confidence.MEDIUM,
                "Path ends in .$ext (HLS playlist extension). Response type not verified."
            )
            ext == "mpd" -> Result(
                MediaType.DASH_MANIFEST, Confidence.MEDIUM,
                "Path ends in .mpd (MPEG-DASH manifest extension). Response type not verified."
            )
            ext == "m4s" || ext == "cmfv" || ext == "cmfa" || ext == "m4f" -> Result(
                MediaType.MEDIA_SEGMENT, Confidence.MEDIUM,
                "Path ends in .m4s (fragmented MP4 segment)."
            )
            ext == "ts" -> if (segmentLike || path.contains("hls") || path.contains("/seg")) {
                Result(MediaType.MEDIA_SEGMENT, Confidence.MEDIUM,
                    ".ts file whose path looks like an HLS segment.")
            } else {
                Result(MediaType.MEDIA_SEGMENT, Confidence.LOW,
                    ".ts extension only; could also be a non-media file.")
            }
            ext in VIDEO_EXT -> if (segmentLike) {
                Result(MediaType.MEDIA_SEGMENT, Confidence.MEDIUM,
                    "Video file whose name looks like a stream segment or init chunk.")
            } else {
                Result(MediaType.DIRECT_VIDEO, Confidence.MEDIUM,
                    "Path ends in .$ext (video container extension). Response type not verified.")
            }
            ext in AUDIO_EXT -> Result(MediaType.AUDIO, Confidence.MEDIUM,
                "Path ends in .$ext (audio-only extension).")
            ext in SUBTITLE_EXT -> Result(MediaType.SUBTITLE, Confidence.MEDIUM,
                "Path ends in .$ext (subtitle/caption extension).")
            ext in IMAGE_EXT -> Result(MediaType.THUMBNAIL, Confidence.MEDIUM,
                "Path ends in .$ext (image extension).")
            else -> null
        }
        if (byExt != null) return boost(byExt, fromVideoElement)

        // 2b) mime= query parameter (googlevideo /videoplayback and similar CDNs).
        if (mime == null) {
            val mp = try { uri?.getQueryParameter("mime") } catch (e: Exception) { null }
            val nm = normalizeMime(mp)
            if (nm != null) {
                classifyMime(nm, segmentLike)?.let {
                    return boost(
                        it.copy(confidence = Confidence.MEDIUM, reason = "Query parameter mime=$nm. Not verified."),
                        fromVideoElement
                    )
                }
            }
        }

        // 3) Weak URL hints for extension-less URLs.
        if (query.contains(".m3u8")) {
            return boost(Result(MediaType.HLS_MANIFEST, Confidence.LOW,
                "URL contains .m3u8 outside the path (for example in a parameter)."), fromVideoElement)
        }
        if (query.contains(".mpd")) {
            return boost(Result(MediaType.DASH_MANIFEST, Confidence.LOW,
                "URL contains .mpd outside the path (for example in a parameter)."), fromVideoElement)
        }
        if (MEDIA_IN_QUERY.containsMatchIn(query)) {
            return boost(Result(MediaType.DIRECT_VIDEO, Confidence.LOW,
                "URL contains a video file name inside a parameter (for example ?file=movie.mp4). Not verified."), fromVideoElement)
        }
        if (MIME_QUERY_HINT.containsMatchIn(query)) {
            return boost(Result(MediaType.DIRECT_VIDEO, Confidence.LOW,
                "Query parameter declares a video MIME type. Not verified."), fromVideoElement)
        }
        if (path.contains("/videoplayback")) {
            return boost(Result(MediaType.DIRECT_VIDEO, Confidence.LOW,
                "Path pattern /videoplayback commonly used by CDN video delivery. Not verified."), fromVideoElement)
        }

        // 4) The page's own <video> element exposed it.
        if (fromVideoElement) {
            return Result(MediaType.DIRECT_VIDEO, Confidence.MEDIUM,
                "Exposed as currentSrc of the page's <video> element; format not recognizable from the URL.")
        }

        return Result(MediaType.UNKNOWN, Confidence.LOW, "No recognizable media signal.")
    }

    private fun classifyMime(mime: String, segmentLike: Boolean): Result? = when {
        mime == "video/mp2t" -> Result(MediaType.MEDIA_SEGMENT, Confidence.HIGH,
            "MIME type video/mp2t (MPEG-TS segment).")
        mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl" ->
            Result(MediaType.HLS_MANIFEST, Confidence.HIGH, "MIME type $mime (HLS playlist).")
        mime == "audio/mpegurl" || mime == "audio/x-mpegurl" ->
            Result(MediaType.HLS_MANIFEST, Confidence.MEDIUM, "MIME type $mime (M3U playlist, usually HLS).")
        mime == "application/dash+xml" ->
            Result(MediaType.DASH_MANIFEST, Confidence.HIGH, "MIME type application/dash+xml (MPEG-DASH manifest).")
        mime.startsWith("video/") ->
            if (segmentLike) Result(MediaType.MEDIA_SEGMENT, Confidence.MEDIUM,
                "MIME type $mime and a segment-like file name.")
            else Result(MediaType.DIRECT_VIDEO, Confidence.HIGH, "MIME type $mime.")
        mime.startsWith("audio/") ->
            Result(MediaType.AUDIO, Confidence.HIGH, "MIME type $mime (audio only).")
        mime == "text/vtt" || mime == "application/x-subrip" || mime == "application/ttml+xml" ->
            Result(MediaType.SUBTITLE, Confidence.HIGH, "MIME type $mime (subtitles).")
        mime.startsWith("image/") ->
            Result(MediaType.THUMBNAIL, Confidence.HIGH, "MIME type $mime (image).")
        else -> null // includes application/octet-stream: fall through to other signals
    }

    private fun boost(r: Result, fromVideoElement: Boolean): Result {
        val mediaLike = r.type == MediaType.DIRECT_VIDEO ||
            r.type == MediaType.HLS_MANIFEST || r.type == MediaType.DASH_MANIFEST
        return if (fromVideoElement && mediaLike) {
            Result(r.type, Confidence.HIGH,
                r.reason + " Also exposed as currentSrc of the page's <video> element.")
        } else r
    }

    fun normalizeMime(mime: String?): String? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return if (m.isNullOrEmpty()) null else m
    }

    private val EMBED_HINTS = listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".m4v", ".mov")

    /**
     * Some sites send the real media link wrapped inside a tracking/analytics URL, e.g.
     * https://tracker.example/kv?...&vurl=https://cdn.example/x/index.m3u8
     * Returns the inner media link in that case; any other URL is returned unchanged.
     */
    fun unwrapEmbeddedMediaUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            if (!uri.isHierarchical) return url
            val path = (uri.path ?: "").lowercase(Locale.ROOT)
            if (EMBED_HINTS.any { path.contains(it) }) return url
            for (name in uri.queryParameterNames) {
                val v = uri.getQueryParameter(name) ?: continue
                val l = v.lowercase(Locale.ROOT)
                if ((l.startsWith("http://") || l.startsWith("https://")) &&
                    EMBED_HINTS.any { l.contains(it) }
                ) return v
            }
            url
        } catch (e: Exception) {
            url
        }
    }

    /** key.m3u8 style files are encryption keys, not playlists. */
    fun isKeyFile(url: String): Boolean {
        val path = try { Uri.parse(url).path } catch (e: Exception) { null } ?: return false
        return path.lowercase(Locale.ROOT).endsWith("key.m3u8")
    }

    /**
     * Key used for deduplication. The query is kept (signed URLs), the fragment is dropped.
     * Segments collapse to one entry per host + directory so a stream does not flood the list.
     */
    fun dedupKey(url: String, type: MediaType): String {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        if ((uri.path ?: "").contains("/videoplayback")) {
            val id = try { uri.getQueryParameter("id") } catch (e: Exception) { null } ?: ""
            val itag = try { uri.getQueryParameter("itag") } catch (e: Exception) { null } ?: ""
            val m = try { uri.getQueryParameter("mime") } catch (e: Exception) { null } ?: ""
            if (id.isNotEmpty() || itag.isNotEmpty()) return "videoplayback|$id|$itag|$m"
        }
        if (type == MediaType.MEDIA_SEGMENT) {
            val host = (uri.host ?: "").lowercase(Locale.ROOT)
            val dir = (uri.path ?: "").substringBeforeLast('/', "")
            return "segments|$host|$dir"
        }
        return uri.buildUpon().fragment(null).build().toString()
    }

    /** googlevideo chunk URLs differ only by range/rn/rbuf; drop those so one stream = one entry. */
    fun canonicalUrl(url: String): String {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        if (!(uri.path ?: "").contains("/videoplayback")) return url
        val q = uri.encodedQuery ?: return url
        val kept = q.split('&').filter {
            it.substringBefore('=').lowercase(Locale.ROOT) !in VOLATILE_PARAMS
        }
        return uri.buildUpon().encodedQuery(kept.joinToString("&")).fragment(null).build().toString()
    }

    /** Some players fetch media with POST (for example SABR). Only these URLs are kept for non-GET. */
    fun isPostMediaHint(url: String): Boolean = url.contains("/videoplayback")

    fun hostOf(url: String): String =
        try { Uri.parse(url).host ?: "unknown" } catch (e: Exception) { "unknown" }

    /** True when the query carries token/signature-style parameters. */
    fun looksAuthProtected(url: String): Boolean {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        if (!uri.isHierarchical) return false
        return uri.queryParameterNames.any { it.lowercase(Locale.ROOT) in AUTH_PARAMS }
    }
}
