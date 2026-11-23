package com.videosniffer.browser

import android.net.Uri
import java.util.Locale

object AdBlockFilter {

    private val whiteListedDomains = listOf(
        "mxplayer.in",
        "mxplayer.com",
        "primevideo.com",
        "amazonvideo.com",
        "media-amazon.com",
        "netflix.com",
        "nflxvideo.net",
        "hotstar.com",
        "akamaized.net",
        "cloudflarestream.com",
        "googlevideo.com"
    )

    private val adDomains = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "amazon-adsystem.com",
        "exoclick.com",
        "exosrv.com",
        "juicyads.com",
        "adsterra.com",
        "clickadu.com",
        "onclickads.net",
        "propellerads.com",
        "popads.net",
        "poponclick.com",
        "trafficjunky.net",
        "trafficjunky.com",
        "mgid.com",
        "outbrain.com",
        "taboola.com",
        "adnxs.com",
        "adsco.re",
        "vidoomy.com",
        "yieldmo.com",
        "smartadserver.com",
        "pubmatic.com",
        "criteo.com",
        "rubiconproject.com",
        "media.net",
        "revcontent.com",
        "adcash.com",
        "bidvertiser.com",
        "hilltopads.net",
        "adskeeper.co.uk",
        "eskimi.com",
        "trafficstars.com",
        "plugrush.com",
        "scorecardresearch.com",
        "hotjar.com",
        "moatads.com"
    )

    private val adPathPatterns = listOf(
        "/banner/",
        "/popunder",
        "/vast",
        "/vpaid",
        "/preroll",
        "/midroll",
        "/postroll",
        "/adserver",
        "/ad_break",
        "/adbreak",
        "/adframe",
        "/adslot"
    )

    fun isAd(url: String): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val host = uri.host
            ?.lowercase(Locale.ROOT)
            ?.trimEnd('.')
            ?: return false

        val lowerUrl = url.lowercase(Locale.ROOT)

        // Legitimate video/CDN domains ko pehle allow karo.
        if (whiteListedDomains.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
        ) {
            return false
        }

        // Exact domain ya uske subdomain ko check karo.
        if (adDomains.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
        ) {
            return true
        }

        // URL ke ad-related path ko check karo.
        if (adPathPatterns.any { pattern ->
                lowerUrl.contains(pattern)
            }
        ) {
            return true
        }

        return false
    }
}