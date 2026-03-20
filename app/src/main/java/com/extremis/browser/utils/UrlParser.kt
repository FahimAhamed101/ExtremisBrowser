package com.extremis.browser.utils

import com.extremis.browser.models.VideoInfo
import com.extremis.browser.models.VideoQuality
import org.json.JSONArray
import java.util.regex.Pattern

class UrlParser {

    fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") ||
                lower.contains(".webm") ||
                lower.contains(".m3u8") ||
                lower.contains(".ogg") ||
                lower.contains(".mov") ||
                lower.contains(".avi") ||
                lower.contains(".mkv") ||
                lower.contains(".flv") ||
                lower.contains(".3gp") ||
                lower.contains("youtube.com/watch") ||
                lower.contains("youtu.be/") ||
                lower.contains("facebook.com/watch") ||
                lower.contains("fb.watch") ||
                lower.contains("instagram.com/p/") ||
                lower.contains("instagram.com/reel/")
    }

    fun parseVideoUrls(jsonResult: String, pageUrl: String): VideoInfo? {
        return try {
            val cleaned = jsonResult.trim().removeSurrounding("\"").replace("\\\"", "\"")
            if (cleaned == "null" || cleaned == "[]" || cleaned.isBlank()) return null

            val jsonArray = JSONArray(cleaned)
            val qualities = mutableListOf<VideoQuality>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val src = obj.optString("src", "")
                val type = obj.optString("type", "video/mp4")
                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)

                if (src.isNotBlank()) {
                    val qualityLabel = when {
                        height > 0 -> "${height}p"
                        else -> extractQuality(src)
                    }
                    val heightStr = when {
                        height > 0 -> height.toString()
                        else -> qualityLabel.replace("p", "").replace("[^0-9]".toRegex(), "")
                            .ifBlank { "480" }
                    }
                    qualities.add(VideoQuality(qualityLabel, src, heightStr, type))
                }
            }

            if (qualities.isNotEmpty()) {
                VideoInfo(
                    title = extractTitle(pageUrl),
                    url = pageUrl,
                    qualities = qualities.sortedBy { it.height.toIntOrNull() ?: 0 }
                )
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun extractYouTubeVideoId(url: String): String? {
        val pattern = Pattern.compile(
            "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([^\"&?/ ]{11})"
        )
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun extractQuality(url: String): String {
        return when {
            url.contains("2160") || url.contains("4k", true) -> "2160p (4K)"
            url.contains("1440") || url.contains("2k", true) -> "1440p (2K)"
            url.contains("1080") -> "1080p (Full HD)"
            url.contains("720") -> "720p (HD)"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            url.contains("144") -> "144p"
            url.contains("hd", true) -> "720p (HD)"
            url.contains("sd", true) -> "480p (SD)"
            else -> "Auto"
        }
    }

    fun extractTitle(url: String): String {
        val lastSegment = url.substringAfterLast("/").substringBefore("?")
        return if (lastSegment.isNotEmpty() && lastSegment.length < 60) {
            lastSegment.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        } else {
            "Video_${System.currentTimeMillis()}"
        }
    }
}
