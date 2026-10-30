package com.extremis.browser.utils

import android.net.Uri

/**
 * The search engines KoiBrowser offered in `MainActivity.SearchEngineSetUp()`, carrying the same
 * query URLs as `constant/SettingsConstant`.
 *
 * [queryUrl] deliberately appends a raw (already percent-encoded) query, matching KoiBrowser's
 * behaviour so that switching engines produces byte-identical URLs.
 */
enum class SearchEngine(
    val key: String,
    val label: String,
    private val baseUrl: String
) {
    GOOGLE("GOOGLE", "Google", "https://www.google.com/search?q="),
    YAHOO("YAHOO", "Yahoo", "https://search.yahoo.com/search?p="),
    BING("BING", "Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DUCKDUCKGO", "DuckDuckGo", "https://duckduckgo.com/?q="),
    ASK("ASK", "Ask", "http://www.ask.com/web?q="),
    BAIDU("BAIDU", "Baidu", "http://www.baidu.com/s?wd="),
    YANDEX("YANDEX", "Yandex", "https://www.yandex.ru/search/?text="),
    LUKAYN("LUKAYN", "Lukayn", "https://lukayn.io/web?q=");

    /** Build a results URL for a raw user query. */
    fun searchUrl(query: String): String = baseUrl + Uri.encode(query)

    /** The origin used for the home-page search bar placeholder and suggestions. */
    val host: String
        get() = Uri.parse(baseUrl).host.orEmpty()

    companion object {
        /** Resolve a persisted key, falling back to Google for unknown/legacy values. */
        fun fromKey(key: String?): SearchEngine {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: GOOGLE
        }
    }
}
