package com.extremis.browser.media

/** How a detected media URL has to be fetched. */
enum class MediaKind {
    /** A single file — mp4, webm, mp3. DownloadManager handles it. */
    DIRECT,

    /** An HLS playlist (`.m3u8`). Segments must be fetched and joined. */
    HLS,

    /** A DASH manifest (`.mpd`). Listed, but not downloadable without a muxer. */
    DASH
}

/**
 * One downloadable stream found on the page.
 *
 * A page normally yields several of these (a 360p and a 720p rendition, a video track and an audio
 * track), so the picker sheet groups them by [kind] and [qualityLabel].
 */
data class MediaItem(
    val url: String,
    val kind: MediaKind,
    val mimeType: String,
    val title: String,
    val host: String,
    val qualityLabel: String,
    /** True when the stream carries no audio track (DASH video-only renditions). */
    val videoOnly: Boolean = false,
    /** Which detector found it — useful when debugging a site that yields nothing. */
    val source: String = SOURCE_NETWORK
) {

    /**
     * Identity used to collapse duplicates. Query strings on media CDNs are full of volatile
     * parameters (`range`, `rn`, `rbum`, expiring signatures), so they are stripped before
     * comparing — otherwise the same stream is listed once per byte-range request.
     */
    val key: String
        get() {
            val base = url.substringBefore('?')
            val volatile = setOf("range", "rn", "rbum", "sq", "gir", "clen", "dur")
            val stable = url.substringAfter('?', "")
                .split('&')
                .filter { it.isNotBlank() }
                .filterNot { param -> volatile.contains(param.substringBefore('=')) }
                .sorted()
                .joinToString("&")
            return "$base?$stable#$qualityLabel"
        }

    /**
     * Short label for the sheet, e.g. `360p · MP4` or `HLS`.
     *
     * Video-only renditions say so explicitly. YouTube serves most resolutions as a separate video
     * track and audio track, so saving one of those produces a silent file — worth stating before
     * the user spends data on it rather than after.
     */
    val summary: String
        get() = when {
            kind == MediaKind.HLS -> "HLS stream"
            kind == MediaKind.DASH -> "DASH manifest"
            videoOnly && qualityLabel.isNotBlank() -> "$qualityLabel · video only, no audio"
            videoOnly -> "$mimeType · video only, no audio"
            qualityLabel.isNotBlank() -> "$qualityLabel · $mimeType"
            else -> mimeType
        }

    companion object {
        const val SOURCE_NETWORK = "network"
        const val SOURCE_PAGE = "page"
        const val SOURCE_YOUTUBE = "youtube"
    }
}
