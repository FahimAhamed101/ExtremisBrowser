package com.extremis.browser.media

import java.util.Locale

/**
 * Decides whether a URL points at something worth downloading.
 *
 * The browser sees every request the page makes, but most of them are HTML, scripts, images and
 * telemetry. Only URLs that pass one of the two rules below are surfaced to the user:
 *
 *  1. the path ends in a media extension, or
 *  2. the host is a known media CDN and the path looks like a media path.
 *
 * Segment files (`.ts`, `.m4s`) are deliberately rejected — they are individual pieces of a stream
 * that has already been reported through its playlist or manifest.
 */
object MediaDetector {

    private val VIDEO_EXTENSIONS = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp",
        "flv" to "video/x-flv"
    )

    private val AUDIO_EXTENSIONS = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav"
    )

    private val PLAYLIST_EXTENSIONS = mapOf(
        "m3u8" to MediaKind.HLS,
        "mpd" to MediaKind.DASH
    )

    /**
     * Extensions that are always fragments of a bigger stream. Listing them would flood the sheet
     * with hundreds of identical-looking entries.
     */
    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "cmfv", "cmfa", "fmp4", "seg", "dash")

    /** Hosts that only ever serve media, so a missing extension is not disqualifying. */
    private val MEDIA_HOSTS = listOf(
        "googlevideo.com",
        "fbcdn.net",
        "cdninstagram.com",
        "video.twimg.com",
        "vimeocdn.com",
        "akamaihd.net",
        "ttvnw.net",
        "dailymotion.com",
        "dmcdn.net",
        "vkuservideo.net",
        "mycdn.me",
        "bilivideo.com",
        "byteoversea.com",
        "tiktokcdn.com",
        "muscdn.com",
        "ixigua.com"
    )

    /** Path fragments that indicate a media resource on a shared CDN. */
    private val MEDIA_PATH_HINTS = listOf(
        "/videos/",
        "/video/",
        "/videoplayback",
        "/media/",
        "/hls/",
        "/dash/",
        "/stream/",
        "/playlist.m3u8",
        "/manifest.mpd"
    )

    /**
     * YouTube's ITag table. Only the values that show up in a browser session are listed; anything
     * unknown still downloads, it just loses its quality label.
     */
    private val ITAG_LABELS = mapOf(
        5 to "240p FLV",
        6 to "270p FLV",
        17 to "144p 3GP",
        18 to "360p",
        22 to "720p",
        34 to "360p FLV",
        35 to "480p FLV",
        36 to "240p 3GP",
        37 to "1080p",
        38 to "3072p",
        43 to "360p WebM",
        44 to "480p WebM",
        45 to "720p WebM",
        46 to "1080p WebM",
        133 to "240p video only",
        134 to "360p video only",
        135 to "480p video only",
        136 to "720p video only",
        137 to "1080p video only",
        138 to "2160p video only",
        139 to "48kbps audio only",
        140 to "128kbps audio only",
        141 to "256kbps audio only",
        160 to "144p video only",
        242 to "240p WebM video only",
        243 to "360p WebM video only",
        244 to "480p WebM video only",
        247 to "720p WebM video only",
        248 to "1080p WebM video only",
        249 to "50kbps Opus audio only",
        250 to "70kbps Opus audio only",
        251 to "160kbps Opus audio only",
        256 to "192kbps audio only",
        258 to "384kbps audio only",
        394 to "144p AV1 video only",
        395 to "240p AV1 video only",
        396 to "360p AV1 video only",
        397 to "480p AV1 video only",
        398 to "720p AV1 video only",
        399 to "1080p AV1 video only"
    )

    /** Video-only ITags, which need a separate audio track to be useful. */
    private val VIDEO_ONLY_ITAGS = setOf(
        133, 134, 135, 136, 137, 138, 160, 242, 243, 244, 247, 248,
        394, 395, 396, 397, 398, 399
    )

    private val AUDIO_ONLY_ITAGS = setOf(139, 140, 141, 249, 250, 251, 256, 258)

    /**
     * @param title page title, used as the download filename.
     * @return the detected item, or null when the URL is not media.
     */
    fun detect(url: String, title: String): MediaItem? {
        if (url.isBlank()) return null
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

        val withoutQuery = lower.substringBefore('?').substringBefore('#')
        val extension = withoutQuery.substringAfterLast('.', "").substringBefore('/')
        val host = hostOf(lower)

        // 1. Playlists — always worth surfacing.
        PLAYLIST_EXTENSIONS[extension]?.let { kind ->
            val mime = if (kind == MediaKind.HLS) {
                "application/vnd.apple.mpegurl"
            } else {
                "application/dash+xml"
            }
            return MediaItem(
                url = url,
                kind = kind,
                mimeType = mime,
                title = title,
                host = host,
                qualityLabel = "",
                source = MediaItem.SOURCE_NETWORK
            )
        }

        // 2. Stream fragments are never listed on their own.
        if (SEGMENT_EXTENSIONS.contains(extension)) return null

        // 3. YouTube / Google Video. These carry no extension at all; the ITag in the query is the
        //    only thing that identifies the rendition.
        if (host.endsWith("googlevideo.com") && withoutQuery.contains("videoplayback")) {
            // Modern YouTube playback streams through the SABR endpoint, which requires a POST
            // carrying a protobuf body. A plain GET of the same URL answers with a 31-byte
            // `sabr.malformed_config` protobuf error — which is exactly the unplayable file this
            // rule used to produce. The player's own format list (YouTubeExtractor) is the only
            // source of URLs that a normal download can actually fetch.
            if (queryParam(url, "sabr") == "1") return null
            return youTubeItem(url, title, host)
        }

        // Every URL below is reduced to the whole resource before it is offered. Media CDNs hand
        // out the *fragment* the player is currently buffering — Facebook, for example, puts the
        // byte window in `bytestart`/`byteend`. Saving that verbatim produces a file that starts
        // at a `moof` box with no `ftyp`/`moov` header, which no player can decode.
        val wholeUrl = stripRangeParams(url)

        // 4. Plain media files.
        VIDEO_EXTENSIONS[extension]?.let { mime ->
            return MediaItem(
                url = wholeUrl,
                kind = MediaKind.DIRECT,
                mimeType = mime,
                title = title,
                host = host,
                qualityLabel = qualityFromUrl(lower),
                source = MediaItem.SOURCE_NETWORK
            )
        }
        AUDIO_EXTENSIONS[extension]?.let { mime ->
            return MediaItem(
                url = wholeUrl,
                kind = MediaKind.DIRECT,
                mimeType = mime,
                title = title,
                host = host,
                qualityLabel = "",
                source = MediaItem.SOURCE_NETWORK
            )
        }

        // 5. Extension-less media on a dedicated media CDN.
        val onMediaHost = MEDIA_HOSTS.any { host.endsWith(it) }
        val mediaPath = MEDIA_PATH_HINTS.any { withoutQuery.contains(it) }
        if (onMediaHost && mediaPath) {
            return MediaItem(
                url = wholeUrl,
                kind = MediaKind.DIRECT,
                mimeType = "video/mp4",
                title = title,
                host = host,
                qualityLabel = qualityFromUrl(lower),
                source = MediaItem.SOURCE_NETWORK
            )
        }

        return null
    }

    /**
     * Build an item for a YouTube `videoplayback` request.
     *
     * The byte-range parameters are stripped so the URL addresses the whole rendition rather than
     * the chunk the player happened to be buffering when we saw it.
     */
    private fun youTubeItem(url: String, title: String, host: String): MediaItem {
        val itag = queryParam(url, "itag")?.toIntOrNull()
        val mime = queryParam(url, "mime")?.let { java.net.URLDecoder.decode(it, "UTF-8") }

        val videoOnly = itag != null && VIDEO_ONLY_ITAGS.contains(itag)
        val audioOnly = itag != null && AUDIO_ONLY_ITAGS.contains(itag)

        val label = when {
            itag == null -> ""
            ITAG_LABELS.containsKey(itag) -> ITAG_LABELS.getValue(itag)
            audioOnly -> "audio only"
            else -> "itag $itag"
        }

        val resolvedMime = when {
            mime != null -> mime.substringBefore(';')
            audioOnly -> "audio/mp4"
            else -> "video/mp4"
        }

        return MediaItem(
            url = stripRangeParams(url),
            kind = MediaKind.DIRECT,
            mimeType = resolvedMime,
            title = title,
            host = host,
            qualityLabel = label,
            videoOnly = videoOnly,
            source = MediaItem.SOURCE_YOUTUBE
        )
    }

    /**
     * Remove the byte-window parameters so the server returns the entire file.
     *
     * `range`/`rn`/`rbum` are YouTube's; `bytestart`/`byteend` are what Facebook and Instagram
     * append to a video URL. Removing them is what turns a single buffered fragment into a
     * complete, playable file.
     */
    fun stripRangeParams(url: String): String {
        val index = url.indexOf('?')
        if (index < 0) return url
        val base = url.substring(0, index)
        val kept = url.substring(index + 1)
            .split('&')
            .filterNot { it.isBlank() }
            .filterNot {
                val name = it.substringBefore('=')
                name == "range" || name == "rn" || name == "rbum" ||
                    name == "bytestart" || name == "byteend"
            }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    fun itagLabel(itag: Int): String = ITAG_LABELS[itag] ?: "itag $itag"

    fun isVideoOnlyItag(itag: Int): Boolean = VIDEO_ONLY_ITAGS.contains(itag)

    fun isAudioOnlyItag(itag: Int): Boolean = AUDIO_ONLY_ITAGS.contains(itag)

    fun mimeForItag(itag: Int): String = when {
        AUDIO_ONLY_ITAGS.contains(itag) -> "audio/mp4"
        else -> "video/mp4"
    }

    private fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
    }

    /** Pull a resolution out of the URL when the site puts one there (`1080p`, `720x480`). */
    private fun qualityFromUrl(url: String): String {
        Regex("""(\d{3,4})p\b""").find(url)?.let { return "${it.groupValues[1]}p" }
        Regex("""(\d{3,4})x(\d{3,4})""").find(url)?.let { return "${it.groupValues[2]}p" }
        return ""
    }

    fun hostOf(url: String): String {
        return try {
            val authority = url.substringAfter("://").substringBefore('/')
            val host = authority.substringAfter('@').substringBefore(':')
            if (host.startsWith("www.")) host.substring(4) else host
        } catch (_: Exception) {
            ""
        }
    }
}
