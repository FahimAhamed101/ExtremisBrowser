package com.extremis.browser.media

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects the media URLs seen while a tab is browsing.
 *
 * Requests are observed on WebView background threads and UI listeners run on the main thread, so
 * every structure here is concurrent and notifications are posted to the main looper.
 *
 * Detections are scoped per tab: switching tabs must not show one page's videos on another, and
 * closing a tab has to drop its list.
 */
object MediaRegistry {

    /** Guard against a pathological page reporting thousands of fragments. */
    private const val MAX_ITEMS_PER_TAB = 80

    private val itemsByTab = ConcurrentHashMap<Long, MutableList<MediaItem>>()
    private val keysByTab = ConcurrentHashMap<Long, MutableSet<String>>()
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Record a detection.
     *
     * @return true when the item was new, false when it was a duplicate.
     */
    fun record(tabId: Long, item: MediaItem): Boolean {
        val seen = keysByTab.getOrPut(tabId) { ConcurrentHashMap.newKeySet() }
        if (!seen.add(item.key)) return false

        val list = itemsByTab.getOrPut(tabId) { CopyOnWriteArrayList() }
        if (list.size >= MAX_ITEMS_PER_TAB) {
            seen.remove(item.key)
            return false
        }
        list.add(item)
        notifyListeners(tabId)
        return true
    }

    /** Convenience wrapper that runs a URL through [MediaDetector] first. */
    fun offer(tabId: Long, url: String, title: String): Boolean {
        val item = MediaDetector.detect(url, title) ?: return false
        return record(tabId, item)
    }

    /**
     * Items for a tab, best-first: progressive streams (audio + video in one file) before
     * video-only and audio-only renditions, then by descending quality.
     */
    fun items(tabId: Long): List<MediaItem> {
        val list = itemsByTab[tabId]?.toList() ?: return emptyList()
        return list.sortedWith(
            compareBy<MediaItem> { if (it.videoOnly) 1 else 0 }
                .thenByDescending { qualityRank(it.qualityLabel) }
        )
    }

    fun count(tabId: Long): Int = itemsByTab[tabId]?.size ?: 0

    fun clear(tabId: Long) {
        itemsByTab.remove(tabId)
        keysByTab.remove(tabId)
        notifyListeners(tabId)
    }

    fun clearAll() {
        val tabs = itemsByTab.keys.toList()
        itemsByTab.clear()
        keysByTab.clear()
        tabs.forEach { notifyListeners(it) }
    }

    fun addListener(listener: (Long) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(tabId: Long) {
        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener(tabId) }
            }
        }
    }

    /** Sort key for a label like `1080p` / `720p video only` / `128kbps audio only`. */
    private fun qualityRank(label: String): Int {
        Regex("""(\d{3,4})p""").find(label)?.let { return it.groupValues[1].toInt() }
        Regex("""(\d{2,3})kbps""").find(label)?.let { return it.groupValues[1].toInt() }
        return 0
    }
}
