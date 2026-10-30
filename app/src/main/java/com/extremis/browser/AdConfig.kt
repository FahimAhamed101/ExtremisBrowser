package com.extremis.browser

/**
 * How often the interrupting ads may appear.
 *
 * The rule of thumb is that an ad must never be the reason someone notices the app: no ad on the
 * first pages of a session, none while a video is playing or full screen, none in incognito, and a
 * long gap between two interstitials. Everything here is also skipped for subscribers.
 */
object AdConfig {

    /** Master switch for the ads that take over the whole screen. */
    const val FULL_SCREEN_ADS_ENABLED = true

    /** A loaded app-open ad is thrown away after this long, as AdMob requires. */
    const val APP_OPEN_AD_EXPIRATION_MILLIS = 4 * 60 * 60 * 1000L

    /** Minimum gap between two interstitials, however fast the user browses. */
    const val INTERSTITIAL_COOLDOWN_MS = 90_000L

    /** Pages that must finish loading before another interstitial is allowed. */
    const val INTERSTITIAL_PAGE_INTERVAL = 3

    /**
     * Pages that must be seen before the first interstitial of a session. Someone who opens the
     * app and reads one page should never meet a full-screen ad.
     */
    const val INTERSTITIAL_WARMUP_PAGES = 3
}
