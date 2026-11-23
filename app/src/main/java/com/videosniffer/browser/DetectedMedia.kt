package com.videosniffer.browser

enum class MediaType(val label: String) {
    DIRECT_VIDEO("Direct Video"),
    HLS_MANIFEST("HLS Manifest"),
    DASH_MANIFEST("DASH Manifest"),
    MEDIA_SEGMENT("Media Segment"),
    AUDIO("Audio"),
    SUBTITLE("Subtitle"),
    THUMBNAIL("Thumbnail"),
    UNKNOWN("Unknown")
}

enum class Confidence(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low")
}

enum class DetectionStatus(val label: String) {
    /** Verified: a probe returned a media MIME type, or the page's own <video> is playing it. */
    CONFIRMED("Confirmed"),

    /** Looks like media from URL/request signals but is not verified. */
    CANDIDATE("Candidate"),

    /** Audio or subtitle resources: shown separately, never presented as video. */
    RELATED("Related resource")
}

enum class DetectionMethod(val label: String) {
    REQUEST_OBSERVED("Request observed (shouldInterceptRequest)"),
    PAGE_OBSERVER("Page <video> element (currentSrc)")
}

data class DetectedMedia(
    val id: Long,
    val tabId: Long,
    val url: String,
    val host: String,
    val type: MediaType,
    val confidence: Confidence,
    val status: DetectionStatus,
    val method: DetectionMethod,
    val reason: String,
    val mimeType: String?,
    val pageUrl: String,
    val pageTitle: String,
    val detectedAtMillis: Long,
    val mayRequireAuth: Boolean,
    /** HLS master playlist (its body lists several quality variants). Seen by the page's JS. */
    val isMaster: Boolean = false
) {
    /** Display-only truncation. Always copy/share/open [url], never this. */
    val shortUrl: String
        get() = if (url.length <= 70) url else url.take(40) + "\u2026" + url.takeLast(24)

    val isVerified: Boolean
        get() = status == DetectionStatus.CONFIRMED
}
