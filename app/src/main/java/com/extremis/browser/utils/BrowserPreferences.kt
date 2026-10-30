package com.extremis.browser.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Central preference store for every browser setting.
 *
 * Ported from KoiBrowser's `utils/Preference`, `constant/SettingsKeys` and
 * `constant/SettingsConstant`. KoiBrowser spread these across a Preference helper plus a pile of
 * string constants and read them from a dozen call sites; here they live behind typed accessors so
 * a typo cannot silently read the wrong key.
 */
class BrowserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** Search engine used for the omnibox and the home search bar. */
    var searchEngine: SearchEngine
        get() = SearchEngine.fromKey(prefs.getString(KEY_SEARCH_ENGINE, null))
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value.key).apply()

    /** Offer Google autocomplete suggestions while typing. */
    var searchSuggestions: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_SUGGESTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_SUGGESTIONS, value).apply()

    /** Persist typed queries into the search-history table. */
    var saveSearchHistory: Boolean
        get() = prefs.getBoolean(KEY_SAVE_SEARCH_HISTORY, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_SEARCH_HISTORY, value).apply()

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /** KoiBrowser's ENABLE_JAVA_SCRIPT_KEY. */
    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JAVASCRIPT, true)
        set(value) = prefs.edit().putBoolean(KEY_JAVASCRIPT, value).apply()

    /** KoiBrowser's ENABLE_IMAGE_LOADING_KEY. */
    var imagesEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAGES, true)
        set(value) = prefs.edit().putBoolean(KEY_IMAGES, value).apply()

    /** KoiBrowser's DESKTOP key — forces the desktop user agent. */
    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP, value).apply()

    /** KoiBrowser's ADBLOCK key. */
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()

    /**
     * Ad-free entitlement, mirrored from the Play subscription.
     *
     * Cached here so ads stay off while the device is offline; BillingManager refreshes it against
     * Play on every start and clears it again when the subscription has lapsed.
     */
    var adFree: Boolean
        get() = prefs.getBoolean(KEY_AD_FREE, false)
        set(value) = prefs.edit().putBoolean(KEY_AD_FREE, value).apply()

    /** Popup blocking via `onCreateWindow`. */
    var blockPopups: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_POPUPS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_POPUPS, value).apply()

    // ------------------------------------------------------------------
    // Privacy
    // ------------------------------------------------------------------

    /** KoiBrowser's ENABLE_INCOGNITO_KEY. */
    var incognitoEnabled: Boolean
        get() = prefs.getBoolean(KEY_INCOGNITO, true)
        set(value) = prefs.edit().putBoolean(KEY_INCOGNITO, value).apply()

    /** KoiBrowser's KEY_CLIPBOARD_MONITOR — offer to open a copied URL. */
    var clipboardMonitor: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_MONITOR, false)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD_MONITOR, value).apply()

    /** KoiBrowser's KEY_AUTO_URL_FILTER. */
    var autoUrlFilter: Boolean
        get() = prefs.getBoolean(KEY_AUTO_URL_FILTER, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_URL_FILTER, value).apply()

    /** Send a notification when a download finishes. */
    var downloadNotifications: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOAD_NOTIFICATIONS, value).apply()

    /** KoiBrowser's KEY_DOWNLOAD_VIA_WIFI_ONLY. */
    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    /** KoiBrowser's DEFAULT_HOME_PAGE_KEY. */
    var homePage: String
        get() = prefs.getString(KEY_HOME_PAGE, DEFAULT_HOME_PAGE).orEmpty()
        set(value) = prefs.edit().putString(KEY_HOME_PAGE, value).apply()

    /** Restore previously open tabs when the browser starts. */
    var restoreTabs: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_TABS, true)
        set(value) = prefs.edit().putBoolean(KEY_RESTORE_TABS, value).apply()

    /** KoiBrowser's KEY_DOWNLOAD_DIRECTORY — absolute path; blank means the system default. */
    var downloadDirectory: String
        get() = prefs.getString(KEY_DOWNLOAD_DIRECTORY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_DIRECTORY, value).apply()

    // ------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------

    /** Running total of requests the ad blocker has stopped. */
    var blockedRequestCount: Long
        get() = prefs.getLong(KEY_BLOCKED_COUNT, 0L)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_COUNT, value).apply()

    /** Number of times the app has been launched (used for rate prompts). */
    var launchCount: Int
        get() = prefs.getInt(KEY_LAUNCH_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAUNCH_COUNT, value).apply()

    /** True once the user has rated or dismissed the rate prompt. */
    var ratePromptHandled: Boolean
        get() = prefs.getBoolean(KEY_RATE_HANDLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RATE_HANDLED, value).apply()

    // ------------------------------------------------------------------
    // Tab persistence (KoiBrowser saved these to SAVED_TABS.parcel)
    // ------------------------------------------------------------------

    var savedTabs: String?
        get() = prefs.getString(KEY_SAVED_TABS, null)
        set(value) = prefs.edit().putString(KEY_SAVED_TABS, value).apply()

    fun registerLaunch() {
        launchCount = launchCount + 1
    }

    fun resetStats() {
        blockedRequestCount = 0L
    }

    companion object {
        const val DEFAULT_HOME_PAGE = "https://updates.today/"

        private const val PREFS_NAME = "extremis_browser_prefs"

        private const val KEY_SEARCH_ENGINE = "SEARCH_ENGINE_KEY"
        private const val KEY_SEARCH_SUGGESTIONS = "SEARCH_SUGGESTIONS_KEY"
        private const val KEY_SAVE_SEARCH_HISTORY = "CLEAR_BROWSER_SEARCH_HISTORY"
        private const val KEY_JAVASCRIPT = "ENABLE_JAVA_SCRIPT_KEY"
        private const val KEY_IMAGES = "ENABLE_IMAGE_LOADING_KEY"
        private const val KEY_DESKTOP = "DESKTOP"
        private const val KEY_ADBLOCK = "ADBLOCK"

        /** Mirror of the Play "remove ads" subscription, so ads stay off while offline. */
        private const val KEY_AD_FREE = "AD_FREE_KEY"
        private const val KEY_BLOCK_POPUPS = "BLOCK_POPUPS_KEY"
        private const val KEY_INCOGNITO = "ENABLE_INCOGNITO_KEY"
        private const val KEY_CLIPBOARD_MONITOR = "CLIPBOARD_MONITOR_KEY"
        private const val KEY_AUTO_URL_FILTER = "KEY_AUTO_URL_FILTER"
        private const val KEY_DOWNLOAD_NOTIFICATIONS = "DOWNLOAD_NOTIFICATIONS_KEY"
        private const val KEY_WIFI_ONLY = "KEY_DOWNLOAD_VIA_WIFI_ONLY"
        private const val KEY_HOME_PAGE = "DEFAULT_HOME_PAGE_KEY"
        private const val KEY_DOWNLOAD_DIRECTORY = "KEY_DOWNLOAD_DIRECTORY"
        private const val KEY_RESTORE_TABS = "KEY_RESTORE_TABS"
        private const val KEY_BLOCKED_COUNT = "BLOCKED_REQUEST_COUNT"
        private const val KEY_LAUNCH_COUNT = "NUMBER_OF_LAUNCH"
        private const val KEY_RATE_HANDLED = "RATE_PROMPT_HANDLED"
        private const val KEY_SAVED_TABS = "SAVED_TABS"

        @Volatile
        private var instance: BrowserPreferences? = null

        fun get(context: Context): BrowserPreferences {
            return instance ?: synchronized(this) {
                instance ?: BrowserPreferences(context).also { instance = it }
            }
        }
    }
}
