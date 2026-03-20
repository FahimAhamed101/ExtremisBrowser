package com.extremis.browser.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import com.extremis.browser.models.VideoInfo
import com.extremis.browser.models.VideoQuality
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class VideoDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ── Download ────────────────────────────────────────────────────────

    fun downloadVideo(videoInfo: VideoInfo, quality: VideoQuality): Long {
        val fileName = generateFileName(videoInfo.title, quality)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ExtremisBrowser"
        )
        dir.mkdirs()

        val request = DownloadManager.Request(Uri.parse(quality.url)).apply {
            setTitle(videoInfo.title)
            setDescription("Downloading ${quality.label}")
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "ExtremisBrowser/$fileName"
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setMimeType("video/mp4")
            addRequestHeaders(this, quality.url)
        }

        return downloadManager.enqueue(request)
    }

    private fun addRequestHeaders(request: DownloadManager.Request, url: String) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        request.addRequestHeader("User-Agent", ua)

        // Forward cookies so authenticated video streams work
        CookieManager.getInstance().getCookie(url)?.let { cookies ->
            request.addRequestHeader("Cookie", cookies)
        }

        when {
            url.contains("facebook.com") || url.contains("fbcdn.net") -> {
                request.addRequestHeader("Referer", "https://www.facebook.com/")
                request.addRequestHeader("Accept", "video/webm,video/mp4,video/*;q=0.9,*/*;q=0.8")
            }
            url.contains("googleusercontent.com") || url.contains("drive.google.com") -> {
                request.addRequestHeader("Referer", "https://drive.google.com/")
            }
        }
    }

    // ── Quality extraction per platform ─────────────────────────────────

    suspend fun extractVideoQualities(videoUrl: String): List<VideoQuality> =
        withContext(Dispatchers.IO) {
            try {
                when {
                    videoUrl.contains("facebook.com") || videoUrl.contains("fb.watch") ->
                        extractFacebookQualities(videoUrl)
                    videoUrl.contains("drive.google.com") || videoUrl.contains("docs.google.com") ->
                        extractGoogleDriveQualities(videoUrl)
                    else ->
                        extractGenericQualities(videoUrl)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    // ── Facebook ────────────────────────────────────────────────────────

    private fun extractFacebookQualities(videoUrl: String): List<VideoQuality> {
        val html = fetchPage(videoUrl) ?: return emptyList()
        val qualities = mutableListOf<VideoQuality>()

        // SD
        findFirst(html, "sd_src\":\"(.*?)\"")?.let { sdUrl ->
            qualities.add(VideoQuality("480p (SD)", unescape(sdUrl), "480", "SD"))
        }
        findFirst(html, "sd_src_no_ratelimit\":\"(.*?)\"")?.let { sdUrl ->
            if (qualities.none { it.height == "480" }) {
                qualities.add(VideoQuality("480p (SD)", unescape(sdUrl), "480", "SD"))
            }
        }

        // HD
        findFirst(html, "hd_src\":\"(.*?)\"")?.let { hdUrl ->
            qualities.add(VideoQuality("720p (HD)", unescape(hdUrl), "720", "HD"))
        }
        findFirst(html, "hd_src_no_ratelimit\":\"(.*?)\"")?.let { hdUrl ->
            if (qualities.none { it.height == "720" }) {
                qualities.add(VideoQuality("720p (HD)", unescape(hdUrl), "720", "HD"))
            }
        }

        // Generic fallback
        if (qualities.isEmpty()) {
            findFirst(html, "video_url\":\"(.*?)\"")?.let { url ->
                qualities.add(VideoQuality("480p (SD)", unescape(url), "480", "SD"))
            }
            findFirst(html, "playable_url\":\"(.*?)\"")?.let { url ->
                if (qualities.isEmpty()) {
                    qualities.add(VideoQuality("480p (SD)", unescape(url), "480", "SD"))
                }
            }
            findFirst(html, "playable_url_quality_hd\":\"(.*?)\"")?.let { url ->
                qualities.add(VideoQuality("720p (HD)", unescape(url), "720", "HD"))
            }
        }

        return qualities.distinctBy { it.height }.sortedBy { it.height.toIntOrNull() ?: 0 }
    }

    // ── Google Drive ────────────────────────────────────────────────────

    private fun extractGoogleDriveQualities(videoUrl: String): List<VideoQuality> {
        val fileId = extractGoogleDriveFileId(videoUrl) ?: return listOf(
            VideoQuality("Original Quality", videoUrl, "720", "Original")
        )

        val qualities = mutableListOf<VideoQuality>()

        // Try streaming endpoint
        val streamUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        qualities.add(VideoQuality("Original Quality", streamUrl, "720", "Original"))

        // Try to get available formats from the video page
        val infoUrl = "https://drive.google.com/get_video_info?docid=$fileId"
        val info = fetchPage(infoUrl)
        if (info != null) {
            val fmtMap = parseQueryParam(info, "fmt_stream_map")
            fmtMap?.split(",")?.forEach { entry ->
                val parts = entry.split("|")
                if (parts.size == 2) {
                    val (itag, url) = parts
                    val label = itagToQuality(itag)
                    val h = label.replace("[^0-9]".toRegex(), "").ifBlank { "480" }
                    qualities.add(VideoQuality(label, url, h, "MP4"))
                }
            }
        }

        return qualities.distinctBy { it.height }.sortedBy { it.height.toIntOrNull() ?: 0 }
    }

    private fun extractGoogleDriveFileId(url: String): String? {
        listOf(
            Pattern.compile("/file/d/([^/]+)"),
            Pattern.compile("id=([^&]+)"),
            Pattern.compile("/document/d/([^/]+)")
        ).forEach { p ->
            val m = p.matcher(url)
            if (m.find()) return m.group(1)
        }
        return null
    }

    private fun itagToQuality(itag: String): String = when (itag) {
        "5", "36" -> "240p"
        "18", "34" -> "360p"
        "35" -> "480p"
        "22", "43", "44" -> "720p"
        "37", "45", "46" -> "1080p"
        "38" -> "1440p (2K)"
        else -> "480p"
    }

    // ── Generic (HTML5 video / source / OpenGraph) ──────────────────────

    private fun extractGenericQualities(videoUrl: String): List<VideoQuality> {
        // Direct video file?
        if (videoUrl.matches(
                Regex(".*\\.(mp4|webm|ogg|mov|avi|mkv|flv|3gp|m4v)(\\?.*)?$", RegexOption.IGNORE_CASE)
            )
        ) {
            return listOf(VideoQuality("Original Quality", videoUrl, "720", "Original"))
        }

        val html = fetchPage(videoUrl) ?: return emptyList()
        val qualities = mutableListOf<VideoQuality>()

        // <source src="..." type="video/...">
        val srcPattern = Pattern.compile(
            "<source[^>]*src=[\"']([^\"']+)[\"'][^>]*type=[\"']video/([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE
        )
        val srcMatcher = srcPattern.matcher(html)
        while (srcMatcher.find()) {
            val src = srcMatcher.group(1) ?: continue
            val fullUrl = resolveUrl(videoUrl, src)
            val label = UrlParser().extractQuality(fullUrl)
            val h = label.replace("[^0-9]".toRegex(), "").ifBlank { "480" }
            qualities.add(VideoQuality(label, fullUrl, h, "MP4"))
        }

        // <video src="...">
        val videoTagPattern = Pattern.compile(
            "<video[^>]*src=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        )
        val videoTagMatcher = videoTagPattern.matcher(html)
        while (videoTagMatcher.find()) {
            val src = videoTagMatcher.group(1) ?: continue
            val fullUrl = resolveUrl(videoUrl, src)
            if (qualities.none { it.url == fullUrl }) {
                val label = UrlParser().extractQuality(fullUrl)
                val h = label.replace("[^0-9]".toRegex(), "").ifBlank { "480" }
                qualities.add(VideoQuality(label, fullUrl, h, "MP4"))
            }
        }

        // OpenGraph og:video
        val ogPattern = Pattern.compile(
            "<meta[^>]*property=[\"']og:video(?::url)?[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        )
        val ogMatcher = ogPattern.matcher(html)
        if (ogMatcher.find()) {
            val ogUrl = ogMatcher.group(1) ?: ""
            if (ogUrl.isNotBlank() && qualities.none { it.url == ogUrl }) {
                qualities.add(VideoQuality("720p (HD)", ogUrl, "720", "MP4"))
            }
        }

        return qualities.distinctBy { it.url }.sortedBy { it.height.toIntOrNull() ?: 0 }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun fetchPage(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(request).execute().use { resp -> resp.body?.string() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun findFirst(text: String, regex: String): String? {
        val m = Pattern.compile(regex).matcher(text)
        return if (m.find()) m.group(1) else null
    }

    private fun unescape(raw: String): String =
        raw.replace("\\/", "/").replace("\\u0025", "%")

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http")) return relative
        return try {
            java.net.URL(java.net.URL(base), relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    private fun parseQueryParam(qs: String, key: String): String? {
        qs.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == key) return java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        return null
    }

    private fun generateFileName(title: String, quality: VideoQuality): String {
        val safe = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60)
        return "${safe}_${quality.label.replace(" ", "")}_${System.currentTimeMillis()}.mp4"
    }

    fun cancelDownload(downloadId: Long) {
        downloadManager.remove(downloadId)
    }
}
