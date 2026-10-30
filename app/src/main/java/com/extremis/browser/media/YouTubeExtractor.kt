package com.extremis.browser.media

import org.json.JSONArray

/**
 * Turns YouTube's own format list into [MediaItem]s.
 *
 * The JSON arrives from `page_media.js`, which reads `window.ytInitialPlayerResponse`.
 *
 * What is deliberately *not* attempted: deciphering `signatureCipher`. YouTube signs most
 * renditions and the signature is only reversible by running the player's obfuscated JavaScript.
 * Those entries are dropped rather than offered as a download that would fail. In practice the
 * progressive renditions (itag 18/22) still carry a plain URL, and those are what a browser can
 * legitimately save.
 */
object YouTubeExtractor {

    fun isYouTubeUrl(url: String): Boolean {
        val host = MediaDetector.hostOf(url.lowercase())
        if (host.isEmpty()) return false
        return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtube-nocookie.com") ||
            host == "youtube-nocookie.com"
    }

    /** True when the URL is YouTube *and* points at something playable rather than the home page. */
    fun isWatchUrl(url: String): Boolean {
        if (!isYouTubeUrl(url)) return false
        val lower = url.lowercase()
        return lower.contains("/watch") || lower.contains("/shorts/") || lower.contains("youtu.be/")
    }

    fun parse(json: String?, pageTitle: String): List<MediaItem> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()

        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }

        val results = mutableListOf<MediaItem>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue

            // Signed-only renditions cannot be fetched without the player's decipher routine.
            if (entry.optBoolean("cipher", false)) continue

            val url = entry.optString("url", "")
            if (url.isBlank() || !url.startsWith("http")) continue

            val itag = entry.optInt("itag", 0)
            val mime = entry.optString("mime", "").substringBefore(';')
            val rawLabel = entry.optString("label", "")

            val audioOnly = MediaDetector.isAudioOnlyItag(itag)
            val videoOnly = MediaDetector.isVideoOnlyItag(itag)

            val label = when {
                rawLabel.isNotBlank() -> rawLabel
                itag != 0 -> MediaDetector.itagLabel(itag)
                else -> ""
            }

            val resolvedMime = when {
                mime.isNotBlank() -> mime
                audioOnly -> "audio/mp4"
                else -> "video/mp4"
            }

            results.add(
                MediaItem(
                    url = MediaDetector.stripRangeParams(url),
                    kind = MediaKind.DIRECT,
                    mimeType = resolvedMime,
                    title = pageTitle,
                    host = MediaDetector.hostOf(url),
                    qualityLabel = label,
                    videoOnly = videoOnly,
                    source = MediaItem.SOURCE_YOUTUBE
                )
            )
        }
        return results
    }
}
