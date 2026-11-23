package com.videosniffer.browser

/**
 * JavaScript injected into every page (and every iframe when DOCUMENT_START_SCRIPT is supported).
 *
 * It reports media URLs that shouldInterceptRequest cannot see or cannot classify:
 *  - fetch()/XHR responses whose Content-Type is media (verified MIME)
 *  - media URLs embedded in JSON/HTML/JS text (very common for stream URLs)
 *  - <video>/<source> src, including videos inside iframes
 *  - blob: (MediaSource) players, so the user gets a clear status message
 *
 * NOTE: no dollar-sign characters inside this string (Kotlin raw string templates).
 */
object SnifferScript {
    const val JS = """
(function () {
  if (window.__vsHooked) return;
  window.__vsHooked = true;
  var sent = {};
  var count = 0;

  // The Java bridge can appear a moment after document start, so reports are queued
  // and delivered as soon as it exists (nothing is lost, nothing throws).
  var pending = [];
  function flush() {
    try {
      var B = window.VideoSnifferBridge;
      if (!B) return;
      while (pending.length) {
        var a = pending.shift();
        try { B.onMediaReport(a[0], a[1], a[2], a[3], a[4], a[5]); } catch (e) {}
      }
    } catch (e) {}
  }
  setInterval(flush, 1000);

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return ''; }
  }

  function send(url, mime, source, playing) {
    try {
      if (!url || typeof url !== 'string') return;
      url = abs(url);
      if (!/^https?:/i.test(url)) return;
      var k = url + '|' + (playing ? 1 : 0) + '|' + (mime || '') + '|' + (source || '');
      if (sent[k] || count > 600) return;
      sent[k] = 1;
      count++;
      pending.push([url, mime || '', source || 'page', !!playing,
                    document.title || '', location.href]);
      flush();
    } catch (e) {}
  }

  function isMediaCt(ct) {
    return /^(video|audio)\/|mpegurl|dash\+xml/i.test(ct || '');
  }

  // HLS playlist by content-type or by .m3u8 in the URL (many servers send text/plain).
  function isM3u(ct, u) {
    return /mpegurl/i.test(ct || '') || /\.m3u8(?![a-z0-9])/i.test(u || '');
  }

  var MEDIA_RE = /https?:(?:\\?\/){2}(?:[^\s"'<>\\]|\\\/)+?\.(?:m3u8|mpd|mp4|webm|m4v|mov|mkv|mp3|m4a)(?![a-z0-9])(?:\?(?:[^\s"'<>\\]|\\\/|\\u0026)*)?/gi;

  function scanText(t, source) {
    try {
      if (!t || t.length > 3000000) return;
      var m = t.match(MEDIA_RE);
      if (!m) return;
      for (var i = 0; i < m.length && i < 25; i++) {
        var u = m[i].replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
        send(u, '', source, false);
      }
    } catch (e) {}
  }

  // ---------- fetch ----------
  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function (input, init) {
        var reqUrl = '';
        try { reqUrl = (typeof input === 'string') ? input : ((input && input.url) || ''); } catch (e) {}
        var p = origFetch.apply(this, arguments);
        try {
          p.then(function (res) {
            try {
              var ct = (res.headers && res.headers.get('content-type')) || '';
              var ru = res.url || reqUrl;
              if (isMediaCt(ct) || isM3u(ct, ru)) {
                send(ru, ct, 'fetch', false);
                if (isM3u(ct, ru)) {
                  res.clone().text().then(function (t) {
                    if (t && t.indexOf('#EXT-X-STREAM-INF') !== -1) send(ru, ct, 'fetch-master', false);
                  }).catch(function () {});
                }
              } else if (/json|text|javascript|xml/i.test(ct)) {
                var len = parseInt((res.headers && res.headers.get('content-length')) || '0', 10);
                if (!len || len < 1500000) {
                  res.clone().text().then(function (t) { scanText(t, 'fetch-body'); }).catch(function () {});
                }
              }
            } catch (e) {}
          }).catch(function () {});
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}

  // ---------- XMLHttpRequest ----------
  try {
    var xOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      try { this.__vsUrl = u; } catch (e) {}
      return xOpen.apply(this, arguments);
    };
    var xSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      try {
        x.addEventListener('readystatechange', function () {
          try {
            if (x.readyState === 2) {
              var ct = x.getResponseHeader('content-type') || '';
              if (isMediaCt(ct) || isM3u(ct, x.responseURL || x.__vsUrl)) {
                send(x.responseURL || x.__vsUrl, ct, 'xhr', false);
              }
            } else if (x.readyState === 4) {
              var ct2 = x.getResponseHeader('content-type') || '';
              var ru2 = x.responseURL || x.__vsUrl || '';
              var textual = (x.responseType === '' || x.responseType === 'text');
              if (textual && isM3u(ct2, ru2)) {
                if ((x.responseText || '').indexOf('#EXT-X-STREAM-INF') !== -1) {
                  send(ru2, ct2, 'xhr-master', false);
                }
              }
              if (textual && /json|text|javascript|xml/i.test(ct2)) {
                scanText(x.responseText, 'xhr-body');
              }
            }
          } catch (e) {}
        });
      } catch (e) {}
      return xSend.apply(this, arguments);
    };
  } catch (e) {}

  // ---------- video.src setter ----------
  try {
    var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (d && d.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true,
        enumerable: d.enumerable,
        get: d.get,
        set: function (v) {
          try { if (typeof v === 'string' && !/^(blob|data):/i.test(v)) send(v, '', 'video-src', false); } catch (e) {}
          return d.set.call(this, v);
        }
      });
    }
  } catch (e) {}

  // ---------- <video> elements ----------
  function reportVideo(v) {
    try {
      var playing = !v.paused && !v.ended && v.readyState >= 2;
      var u = v.currentSrc || v.src || '';
      if (u && /^blob:/i.test(u)) {
        if (!window.__vsBlobNotified) {
          window.__vsBlobNotified = true;
          if (window.VideoSnifferBridge) window.VideoSnifferBridge.onBlobVideo(location.href);
        }
      } else if (u) {
        send(u, '', 'video', playing);
      }
      var s = v.querySelectorAll ? v.querySelectorAll('source') : [];
      for (var i = 0; i < s.length; i++) {
        if (s[i].src && !/^blob:/i.test(s[i].src)) send(s[i].src, s[i].type || '', 'video-source', false);
      }
    } catch (e) {}
  }

  function scanVideos() {
    try {
      var list = document.querySelectorAll('video');
      for (var i = 0; i < list.length; i++) {
        var v = list[i];
        if (!v.__vsListening) {
          v.__vsListening = true;
          ['play', 'playing', 'loadedmetadata', 'loadeddata'].forEach(function (ev) {
            v.addEventListener(ev, function () { reportVideo(v); }, true);
          });
        }
        reportVideo(v);
      }
    } catch (e) {}
  }

  var timer = null;
  function scheduleScan() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; scanVideos(); }, 300);
  }

  try {
    new MutationObserver(scheduleScan).observe(document, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['src']
    });
  } catch (e) {}

  document.addEventListener('playing', function (e) {
    if (e.target && e.target.tagName === 'VIDEO') reportVideo(e.target);
  }, true);

  function scanHtml() {
    try {
      var el = document.documentElement;
      if (el) scanText(el.outerHTML, 'html');
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', function () { scanHtml(); scanVideos(); });
  window.addEventListener('load', function () { scanHtml(); scanVideos(); });

  setInterval(scanVideos, 2000);
})();
"""
}
