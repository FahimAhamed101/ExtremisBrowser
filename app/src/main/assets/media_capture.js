/*
 * ExtremisBrowser media capture hook.
 *
 * The URLs YouTube hands out are signature-protected and the media itself arrives over SABR, a
 * POST protocol a WebView cannot replay. But every player has to pass the decoded bytes to
 * SourceBuffer.appendBuffer before it can render them, so copying that payload yields the real
 * media with no cipher and no signature involved. It works for any site that streams through
 * MediaSource — YouTube, Instagram, Facebook, most DASH players.
 *
 * This file is injected with WebViewCompat.addDocumentStartJavaScript, which runs before any page
 * script. That timing is the whole point: addSourceBuffer has to be replaced before the player
 * creates its source buffers. Injecting on page finish is always too late for a page opened
 * directly on a video, because the player buffers immediately.
 *
 * The hook is also injected again on page finish as a fallback for WebView builds without the
 * document-start API; __extremisCaptureInstalled keeps that from installing twice.
 */
(function () {
  try {

    if (window.__extremisCaptureInstalled) return;
    if (typeof MediaSource === 'undefined' || !MediaSource.prototype) return;
    if (typeof SourceBuffer === 'undefined' || !SourceBuffer.prototype) return;

    var mediaSourcePrototype = MediaSource.prototype;
    var sourceBufferPrototype = SourceBuffer.prototype;
    if (!mediaSourcePrototype.addSourceBuffer || !sourceBufferPrototype.appendBuffer) return;

    window.__extremisCaptureInstalled = true;

    /*
     * Keep the session on VP9/WebM.
     *
     * YouTube sometimes moves to AV1 (MP4) partway through a session — typically right after
     * playback starts or when it changes quality. That builds a new player, so the video track
     * restarts in a different container while the audio track stays Opus/WebM, and the two can no
     * longer be muxed into one file. Claiming no AV1 support keeps video and audio in the same
     * family for the whole session. VP9 is universally supported, so nothing is lost.
     */
    try {
      if (MediaSource.isTypeSupported) {
        var originalIsTypeSupported = MediaSource.isTypeSupported;
        MediaSource.isTypeSupported = function (type) {
          try {
            if (/av01|av1\./i.test(String(type))) return false;
          } catch (e) { }
          return originalIsTypeSupported.apply(this, arguments);
        };
      }
    } catch (e) { }

    // Matches MediaCapture.MAX_BYTES_PER_TRACK on the app side.
    var CAPTURE_LIMIT = 128 * 1024 * 1024;
    var capturedBytes = 0;
    var videoBuffer = null;

    /*
     * Looked up per call rather than once at install time: this script runs at document start,
     * which is before the app's JavaScript bridge is necessarily visible on the window.
     */
    function bridge() {
      var target = window.ExtremisMedia;
      if (!target || !target.postMediaChunk) return null;
      return target;
    }

    function toBase64(bytes) {
      var binary = '';
      var step = 0x8000;
      for (var i = 0; i < bytes.length; i += step) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
      }
      return btoa(binary);
    }

    function bytesOf(data) {
      if (!data) return null;
      if (data instanceof ArrayBuffer) return new Uint8Array(data);
      if (data.buffer) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      return null;
    }

    function kindFromMime(mime) {
      var lower = String(mime || '').toLowerCase();
      if (lower.indexOf('audio') === 0) return 'audio';
      if (lower.indexOf('video') === 0) return 'video';
      return '';
    }

    /*
     * Init segments carry the codec, so a SourceBuffer created before this hook was installed can
     * still be classified from its first payload. MP4 names its codecs as fourcc boxes; WebM uses
     * V_/A_ codec ids.
     */
    function kindFromInit(bytes) {
      var limit = Math.min(bytes.length, 4096);
      var text = '';
      for (var i = 0; i < limit; i++) text += String.fromCharCode(bytes[i]);
      if (/avc1|avc3|hvc1|hev1|vp09|vp08|av01|dvh1|dvhe|V_VP8|V_VP9|V_AV1/.test(text)) {
        return 'video';
      }
      if (/mp4a|opus|vorbis|ac-3|ec-3|flac|alac|A_OPUS|A_VORBIS/.test(text)) {
        return 'audio';
      }
      return '';
    }

    // Record the mime type so the append hook does not have to guess for buffers we saw created.
    var addSourceBuffer = mediaSourcePrototype.addSourceBuffer;
    mediaSourcePrototype.addSourceBuffer = function (mime) {
      var buffer = addSourceBuffer.apply(this, arguments);
      try {
        if (buffer) {
          buffer.__extremisMime = mime;
          buffer.__extremisKind = kindFromMime(mime);
        }
      } catch (e) { }
      return buffer;
    };

    /*
     * Patched on the prototype rather than per instance: a SourceBuffer created before this script
     * ran still goes through here, so a late injection can still capture the tail of a stream.
     */
    var appendBuffer = sourceBufferPrototype.appendBuffer;
    sourceBufferPrototype.appendBuffer = function (data) {
      try {
        var bytes = bytesOf(data);
        if (bytes && bytes.length) {
          var kind = this.__extremisKind;
          if (!kind) {
            kind = kindFromInit(bytes);
            if (kind) this.__extremisKind = kind;
          }

          if (kind === 'video') {
            // A different video SourceBuffer appearing while we already hold bytes means the page
            // moved on to another video (a Shorts swipe) — the capture has to start over or two
            // videos end up spliced into one file.
            if (videoBuffer && videoBuffer !== this && capturedBytes > 0) {
              capturedBytes = 0;
              var resetTarget = bridge();
              try { if (resetTarget && resetTarget.resetMediaCapture) resetTarget.resetMediaCapture(); } catch (e) { }
            }
            videoBuffer = this;
          }

          var target = bridge();
          if (kind && target && capturedBytes < CAPTURE_LIMIT) {
            capturedBytes += bytes.length;
            target.postMediaChunk(kind, toBase64(bytes));
          }
        }
      } catch (e) { }

      return appendBuffer.apply(this, arguments);
    };

    /*
     * Collect the rest of the video.
     *
     * A page only feeds the player the segments it is about to show, so a capture taken while the
     * user watches contains just the part they reached. Pressing play makes the player fetch every
     * remaining segment, which the append hook above collects, so a download no longer depends on
     * the user sitting through the clip.
     *
     * Playback stays at normal speed on purpose. A fast rate makes the player drop to a lower
     * quality mid-stream, and a quality change replaces the video stream — after which the video
     * and audio captures no longer belong together and cannot be muxed into one file.
     */
    var driveTimer = null;

    function notifyReady(reason) {
      var target = bridge();
      try { if (target && target.onFullCaptureReady) target.onFullCaptureReady(reason); } catch (e) { }
    }

    window.__extremisCaptureFull = function () {
      try {
        if (driveTimer) return;

        var video = document.querySelector('video');
        if (!video) {
          notifyReady('no-video');
          return;
        }

        var restoreMuted = video.muted;
        var startedAt = Date.now();
        var lastPercent = -1;
        var lastProgressAt = Date.now();

        // A live stream has no end to reach, so it is left alone.
        var duration = isFinite(video.duration) ? video.duration : 0;

        // As long as the clip plus slack for buffering, capped so a stuck player cannot hang on
        // forever. A stalled buffer is caught separately, below.
        var TIMEOUT_MS = duration > 0
          ? Math.min(duration * 1000 + 90000, 3 * 60 * 60 * 1000)
          : 30 * 60 * 1000;

        function target() {
          if (duration > 0) return duration;
          try {
            var seekable = video.seekable;
            if (seekable && seekable.length) return seekable.end(seekable.length - 1);
          } catch (e) { }
          return 0;
        }

        function coverage() {
          try {
            var buffered = video.buffered;
            if (!buffered || !buffered.length) return 0;
            var end = buffered.end(buffered.length - 1);
            var total = target();
            return total > 0 ? Math.min(1, end / total) : 0;
          } catch (e) {
            return 0;
          }
        }

        function stop(reason) {
          if (driveTimer) {
            clearInterval(driveTimer);
            driveTimer = null;
          }
          try { video.pause(); } catch (e) { }
          try { video.muted = restoreMuted; } catch (e) { }
          notifyReady(reason);
        }

        // Lets the app stop early and keep whatever has been collected so far.
        window.__extremisStopCapture = function () { stop('cancelled'); };

        video.muted = true;
        try {
          var started = video.play();
          if (started && started.catch) started.catch(function () { });
        } catch (e) { }

        driveTimer = setInterval(function () {
          try {
            // Pausing is common once the visible part has played, so playback is re-asserted.
            if (video.paused && !video.ended) {
              try { video.play(); } catch (e) { }
            }

            var percent = Math.round(coverage() * 100);
            var api = bridge();
            try { if (api && api.onFullCaptureProgress) api.onFullCaptureProgress(percent); } catch (e) { }

            if (percent > lastPercent) {
              lastPercent = percent;
              lastProgressAt = Date.now();
            }

            if (target() > 0 && coverage() >= 0.999) { stop('complete'); return; }
            if (video.ended) { stop('ended'); return; }
            if (Date.now() - lastProgressAt > 45000) { stop('stalled'); return; }
            if (Date.now() - startedAt > TIMEOUT_MS) { stop('timeout'); }
          } catch (e) { }
        }, 500);
      } catch (e) {
        notifyReady('failed');
      }
    };

  } catch (e) {
  }
})();
