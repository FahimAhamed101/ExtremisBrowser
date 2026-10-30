package com.extremis.browser.tabs

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the open tabs and swaps the active [WebView] in and out of the content container.
 *
 * Ported from KoiBrowser's `manager/TabsManager`, keeping its tab-removal semantics: when the
 * current tab is closed we move to the *next* tab if one exists to the right, otherwise to the
 * previous one.
 *
 * The big difference is ownership of the WebView. KoiBrowser's `BrowserView` constructed its own
 * WebView; here the caller passes a [webViewFactory] so the activity can attach the shared
 * `WebViewClient` / `WebChromeClient` / settings before the tab is ever shown.
 */
class TabManager(
    private val host: ViewGroup,
    private val webViewFactory: (TabSession) -> WebView
) {

    private val sessions = mutableListOf<TabSession>()

    var currentIndex: Int = 0
        private set

    /** Fired whenever the tab count changes, so the UI can refresh its counter. */
    var onTabCountChanged: ((Int) -> Unit)? = null

    /** Fired when the active tab changes (switch, create, close). */
    var onCurrentTabChanged: ((TabSession) -> Unit)? = null

    val count: Int get() = sessions.size
    val all: List<TabSession> get() = sessions
    val isEmpty: Boolean get() = sessions.isEmpty()

    val current: TabSession?
        get() = sessions.getOrNull(currentIndex)

    fun tabAt(index: Int): TabSession? = sessions.getOrNull(index)

    /**
     * Create a tab. When [activate] is true it becomes the visible tab immediately.
     *
     * [url] is only *recorded* here; loading is the caller's job so that the New Tab Page path and
     * the real-URL path can diverge.
     */
    fun newTab(
        incognito: Boolean = false,
        url: String = TabSession.NEW_TAB_URL,
        title: String = "",
        activate: Boolean = true
    ): TabSession {
        val session = TabSession(
            id = nextTabId(),
            incognito = incognito,
            url = url,
            title = title
        )
        session.webView = webViewFactory(session)
        sessions.add(session)
        onTabCountChanged?.invoke(sessions.size)

        if (activate) {
            select(sessions.lastIndex)
        } else {
            // Keep it detached until it is selected.
            session.webView.visibility = View.GONE
        }
        return session
    }

    /** Show the tab at [index], detaching whichever WebView was previously on screen. */
    fun select(index: Int) {
        if (index !in sessions.indices) return
        val target = sessions[index]

        sessions.getOrNull(currentIndex)?.let { previous ->
            if (previous !== target) {
                previous.webView.onPause()
                (previous.webView.parent as? ViewGroup)?.removeView(previous.webView)
                previous.webView.visibility = View.GONE
            }
        }

        currentIndex = index
        target.lastActiveAt = System.currentTimeMillis()
        target.webView.visibility = View.VISIBLE
        if (target.webView.parent == null) {
            host.addView(
                target.webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        target.webView.onResume()
        onCurrentTabChanged?.invoke(target)
    }

    /**
     * Close the tab at [index] and pick a sensible survivor, mirroring KoiBrowser's `deleteTab`.
     *
     * @return the tab that ended up active, or null when the last tab was closed.
     */
    fun closeTab(index: Int): TabSession? {
        if (index !in sessions.indices) return current
        val wasCurrent = index == currentIndex

        val removed = sessions.removeAt(index)
        removed.webView.onPause()
        (removed.webView.parent as? ViewGroup)?.removeView(removed.webView)
        removed.webView.apply {
            stopLoading()
            clearHistory()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }

        if (sessions.isEmpty()) {
            currentIndex = 0
            onTabCountChanged?.invoke(0)
            return null
        }

        if (wasCurrent) {
            val next = when {
                index < sessions.size -> index
                else -> sessions.lastIndex
            }
            currentIndex = next.coerceIn(sessions.indices)
        } else if (index < currentIndex) {
            currentIndex -= 1
        }
        currentIndex = currentIndex.coerceIn(sessions.indices)

        val survivor = sessions[currentIndex]
        // Re-attach the survivor; select() short-circuits when it is already current.
        survivor.webView.visibility = View.VISIBLE
        if (survivor.webView.parent == null) {
            host.addView(
                survivor.webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        survivor.webView.onResume()
        onTabCountChanged?.invoke(sessions.size)
        onCurrentTabChanged?.invoke(survivor)
        return survivor
    }

    fun indexOf(session: TabSession): Int = sessions.indexOf(session)

    /** KoiBrowser's `resumeAll` — resume timers and every WebView. */
    fun resumeAll() {
        sessions.forEach { it.webView.onResume() }
    }

    /** KoiBrowser's `pauseAll` — pause timers and every WebView. */
    fun pauseAll() {
        sessions.forEach { it.webView.onPause() }
    }

    /** KoiBrowser's `freeMemory` — let the WebView drop caches under pressure. */
    fun freeMemory() {
        sessions.forEach { it.webView.clearCache(false) }
    }

    /** KoiBrowser's `notifyConnectionStatus`. */
    fun setNetworkAvailable(available: Boolean) {
        sessions.forEach { it.webView.setNetworkAvailable(available) }
    }

    /** Tear every tab down. */
    fun shutdown() {
        sessions.forEach { session ->
            (session.webView.parent as? ViewGroup)?.removeView(session.webView)
            session.webView.apply {
                stopLoading()
                clearHistory()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
        }
        sessions.clear()
        currentIndex = 0
        onTabCountChanged?.invoke(0)
    }

    /** Close every tab of the given mode. Returns how many were closed. */
    fun closeIncognitoTabs(): Int {        val doomed = sessions.filter { it.incognito }
        if (doomed.isEmpty()) return 0
        val survivors = sessions.filterNot { it.incognito }
        doomed.forEach { session ->
            (session.webView.parent as? ViewGroup)?.removeView(session.webView)
            session.webView.apply {
                stopLoading()
                clearHistory()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
        }
        sessions.clear()
        sessions.addAll(survivors)
        if (sessions.isEmpty()) {
            currentIndex = 0
        } else {
            currentIndex = currentIndex.coerceIn(sessions.indices)
            val active = sessions[currentIndex]
            active.webView.visibility = View.VISIBLE
            if (active.webView.parent == null) {
                host.addView(
                    active.webView,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        onTabCountChanged?.invoke(sessions.size)
        sessions.getOrNull(currentIndex)?.let { onCurrentTabChanged?.invoke(it) }
        return doomed.size
    }

    // ------------------------------------------------------------------
    // Persistence — KoiBrowser wrote a Bundle to SAVED_TABS.parcel
    // ------------------------------------------------------------------

    fun serialize(): String {
        val array = JSONArray()
        sessions.forEach { session ->
            // Incognito tabs are deliberately never persisted.
            if (session.incognito) return@forEach
            array.put(
                JSONObject().apply {
                    put("url", session.url)
                    put("title", session.title)
                }
            )
        }
        return array.toString()
    }

    /** Returns true when at least one tab was restored. */
    fun restore(serialized: String?): Boolean {
        if (serialized.isNullOrBlank()) return false
        val array = try {
            JSONArray(serialized)
        } catch (_: Exception) {
            return false
        }
        if (array.length() == 0) return false

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url", TabSession.NEW_TAB_URL)
            val title = item.optString("title", "")
            newTab(incognito = false, url = url, title = title, activate = false)
        }
        if (sessions.isEmpty()) return false
        currentIndex = 0
        select(0)
        return true
    }

    companion object {
        /**
         * Process-wide, deliberately not per-instance.
         *
         * [com.extremis.browser.media.MediaRegistry] is a singleton keyed by tab id and outlives any
         * one TabManager, so ids have to stay unique across activity recreation. A per-instance
         * counter would restart at 1 and hand a brand new tab the previous tab's detections.
         */
        private val idGenerator = AtomicLong(1L)

        private fun nextTabId(): Long = idGenerator.getAndIncrement()
    }
}
