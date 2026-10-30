/*
 * ExtremisBrowser media scanner.
 *
 * Injected into every page. It does two jobs:
 *
 *   1. Reports real media URLs found in the DOM, including ones assigned after load.
 *   2. On YouTube, hands the player's own format list back to the app.
 *
 * Blob and data URLs are skipped on purpose: they are MediaSource handles, not downloadable
 * resources, and the bytes behind them are already visible to the app through the network scanner.
 *
 * Everything is wrapped in try/catch — a page that breaks this script must not break the page.
 */
(function () {
  if (window.__extremisScannerInstalled) return;
  window.__extremisScannerInstalled = true;

  var bridge = window.ExtremisMedia;

  function report(url, source) {
    try {
      if (!url || typeof url !== 'string') return;
      if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) return;
      if (!/^https?:/i.test(url)) return;
      if (!bridge || !bridge.postMedia) return;
      bridge.postMedia(url, document.title || '', source || 'page');
    } catch (e) { /* never let the page break because of us */ }
  }

  function scan() {
    try {
      var nodes = document.querySelectorAll('video, audio, source');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.currentSrc) report(el.currentSrc, 'page');
        if (el.src) report(el.src, 'page');
        if (el.querySelectorAll) {
          var kids = el.querySelectorAll('source');
          for (var j = 0; j < kids.length; j++) report(kids[j].src, 'page');
        }
      }
    } catch (e) { }
  }

  // Catch src assigned after the page loaded — most players build the element first.
  try {
    var descriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (descriptor && descriptor.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true,
        enumerable: descriptor.enumerable,
        get: descriptor.get,
        set: function (value) {
          report(value, 'page');
          return descriptor.set.call(this, value);
        }
      });
    }
  } catch (e) { }

  document.addEventListener('loadedmetadata', function (event) {
    try {
      if (event && event.target && event.target.currentSrc) {
        report(event.target.currentSrc, 'page');
      }
    } catch (e) { }
  }, true);

  document.addEventListener('play', function (event) {
    try {
      if (event && event.target && event.target.currentSrc) {
        report(event.target.currentSrc, 'page');
      }
    } catch (e) { }
  }, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scan);
  } else {
    scan();
  }
  setTimeout(scan, 1500);
  setTimeout(scan, 5000);

  // ----------------------------------------------------------------
  // YouTube.
  //
  // Modern YouTube no longer embeds the format list in the document: ytInitialPlayerResponse
  // arrives as a stub holding only `playabilityStatus`, and the real streamingData comes back
  // from a POST to /youtubei/v1/player. A WebView cannot replay that POST — it has no access to
  // the request body — so the formats can only be read from inside the page, by observing the
  // response as it arrives.
  //
  // Entries carrying a plain `url` are downloadable. Entries carrying only `signatureCipher` need
  // YouTube's signature deciphered, which we do not do, so they are flagged and the app drops them.
  // In practice YouTube currently returns only ciphered entries, which is why this yields nothing
  // on most videos.
  // ----------------------------------------------------------------

  /** Turn one YouTube format list into the flat shape the app parses. */
  function pushFormats(list, out) {
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      var format = list[i];
      if (!format) continue;

      var url = format.url || null;
      var ciphered = false;
      if (!url && format.signatureCipher) {
        ciphered = true;
        try {
          var params = new URLSearchParams(format.signatureCipher);
          url = params.get('url');
        } catch (e) { url = null; }
      }
      if (!url) continue;

      out.push({
        itag: format.itag,
        url: url,
        mime: format.mimeType || '',
        label: format.qualityLabel || format.audioQuality || '',
        cipher: ciphered
      });
    }
  }

  /** Post every downloadable rendition in a streamingData block. Returns how many were found. */
  function postFormatsFrom(streamingData) {
    if (!streamingData) return 0;
    var out = [];
    pushFormats(streamingData.formats, out);
    pushFormats(streamingData.adaptiveFormats, out);
    if (out.length && bridge && bridge.postPlayerFormats) {
      bridge.postPlayerFormats(JSON.stringify(out));
    }
    return out.length;
  }

  window.__extremisCollectFormats = function () {
    try {
      var response = window.ytInitialPlayerResponse;
      if (!response && window.ytplayer && window.ytplayer.config) {
        response = window.ytplayer.config.args && window.ytplayer.config.args.player_response
          ? JSON.parse(window.ytplayer.config.args.player_response)
          : null;
      }
      if (!response || !response.streamingData) return '[]';

      var out = [];
      pushFormats(response.streamingData.formats, out);
      pushFormats(response.streamingData.adaptiveFormats, out);
      if (out.length && bridge && bridge.postPlayerFormats) {
        bridge.postPlayerFormats(JSON.stringify(out));
      }
      return JSON.stringify(out);
    } catch (e) {
      return '[]';
    }
  };

  // ----------------------------------------------------------------
  // Capture the player API response.
  //
  // fetch and XHR are both hooked because YouTube picks whichever the browser supports, and the
  // player request is the only place the downloadable URLs appear.
  // ----------------------------------------------------------------
  try {
    var originalFetch = window.fetch;
    if (typeof originalFetch === 'function') {
      window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var promise = originalFetch.apply(this, arguments);
        if (url.indexOf('/youtubei/v1/player') !== -1) {
          promise.then(function (response) {
            try {
              response.clone().text().then(function (text) {
                try {
                  postFormatsFrom(JSON.parse(text).streamingData);
                } catch (e) { }
              }).catch(function () { });
            } catch (e) { }
          }).catch(function () { });
        }
        return promise;
      };
    }
  } catch (e) { }

  try {
    var originalXhrOpen = XMLHttpRequest.prototype.open;
    var originalXhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__extremisUrl = url || '';
      return originalXhrOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      if (String(xhr.__extremisUrl).indexOf('/youtubei/v1/player') !== -1) {
        xhr.addEventListener('load', function () {
          try {
            postFormatsFrom(JSON.parse(xhr.responseText).streamingData);
          } catch (e) { }
        });
      }
      return originalXhrSend.apply(this, arguments);
    };
  } catch (e) { }

  // Capture of the playing video lives in its own asset (media_capture.js), because it has to be
  // installed before any page script runs and this scanner only runs on page finish. See
  // MainActivity.installDocumentStartCapture.

  // The player response is attached well after load, and YouTube Shorts swaps videos without
  // reloading the document, so a single late attempt misses it. Retry a few times early on, and
  // again after every in-page navigation.
  function scheduleCollect(delay) {
    setTimeout(function () {
      try { window.__extremisCollectFormats(); } catch (e) { }
    }, delay);
  }

  scheduleCollect(1500);
  scheduleCollect(3500);
  scheduleCollect(6500);
  scheduleCollect(11000);

  try {
    document.addEventListener('yt-navigate-finish', function () {
      scheduleCollect(900);
      scheduleCollect(2600);
    }, true);
  } catch (e) { }
})();
