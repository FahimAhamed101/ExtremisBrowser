package com.extremis.browser.tabs

import android.webkit.WebView

/**
 * One browser tab: the metadata plus the [WebView] that renders it.
 *
 * KoiBrowser modelled this as `utils/WebPage` and kept a separate `view/BrowserView` (a FrameLayout
 * subclass) per tab. We keep the same one-WebView-per-tab model, but hold the WebView directly
 * instead of wrapping it in a custom FrameLayout — the wrapping was only there to support
 * KoiBrowser's per-tab progress overlay.
 */
class TabSession(
    val id: Long,
    val incognito: Boolean,
    var url: String,
    var title: String
) {
    lateinit var webView: WebView
        internal set

    /**
     * True once [webView] has been assigned.
     *
     * Callers outside this class cannot use `::webView.isInitialized` on another instance
     * (the backing field is not accessible), so they go through this instead.
     */
    val hasWebView: Boolean
        get() = ::webView.isInitialized

    /** Wall-clock of the last time this tab was on screen, used for LRU eviction. */
    var lastActiveAt: Long = System.currentTimeMillis()

    /** True once a page load has been committed, so we know the URL is real. */
    var hasLoadedPage: Boolean = false

    /**
     * URL of the document whose media detections are currently in the scanner.
     *
     * Used to decide whether a page finish means "new document, drop the old list" or
     * "same document reloading, keep what we found".
     */
    var lastScannedUrl: String = ""

    val isNewTab: Boolean
        get() = url == NEW_TAB_URL || url.isBlank()

    companion object {
        /** Sentinel URL for the New Tab Page, matching our existing home handling. */
        const val NEW_TAB_URL = "extremis://newtab"
    }
}
