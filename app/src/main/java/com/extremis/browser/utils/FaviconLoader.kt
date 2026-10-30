package com.extremis.browser.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.extremis.browser.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Minimal favicon loader.
 *
 * Chrome shows a site favicon in the tab switcher, in bookmarks, in history and on the
 * "Most visited" tiles. There is no image library in this project, so this helper fetches
 * the favicon from Google's public favicon endpoint on a small background pool, keeps the
 * decoded bitmaps in an in-memory LRU cache, and falls back to a globe glyph when the
 * lookup fails or the network is unavailable.
 */
object FaviconLoader {

    private const val CACHE_ENTRIES = 96
    private const val FAVICON_ENDPOINT = "https://www.google.com/s2/favicons?sz=64&domain="

    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Extracts a clean host name from a URL, or null when there is nothing usable. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    /**
     * Loads the favicon for [url] into [target]. Always sets the fallback icon first so the
     * view never flashes empty. Pass `0` for [fallbackIconRes] to clear the image instead
     * (used by the "Most visited" tiles, which show a letter behind the favicon).
     * Requests are tagged with the host so a recycled view that has already been rebound
     * ignores the late result.
     */
    fun load(
        url: String?,
        target: ImageView,
        fallbackIconRes: Int = R.drawable.ic_globe_24
    ) {
        val host = hostOf(url)
        target.tag = host
        if (fallbackIconRes == 0) {
            target.setImageDrawable(null)
        } else {
            target.setImageResource(fallbackIconRes)
        }
        if (host == null) return

        val key = host.lowercase()
        cache.get(key)?.let {
            target.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = fetch(key) ?: return@execute
            cache.put(key, bitmap)
            mainHandler.post {
                if (target.tag == host) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetch(host: String): Bitmap? = runCatching {
        val connection = URL(FAVICON_ENDPOINT + host).openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.instanceFollowRedirects = true
        connection.connect()
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** Drops every cached favicon — used when the user clears browsing data. */
    fun clear() = cache.evictAll()

    /**
     * Seed the cache with a favicon the WebView already decoded.
     *
     * `WebChromeClient.onReceivedIcon` hands us the real site icon, which is both more accurate
     * and already in memory — worth keeping so the tab switcher does not re-fetch it over HTTP.
     */
    fun put(url: String?, icon: Bitmap?) {
        val host = hostOf(url) ?: return
        if (icon == null) return
        cache.put(host.lowercase(), icon)
    }
}
