package com.extremis.browser.utils

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.extremis.browser.IncognitoModeState
import com.extremis.browser.R
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Utility class to simplify loading and lifecycle management of AdMob Banner Ads across activities.
 */
object AdHelper {

    fun loadBannerAd(
        activity: Activity,
        container: FrameLayout,
        onBannerCreated: (AdView) -> Unit
    ) {
        // Incognito and the paid subscription both mean no ads on any screen.
        if (IncognitoModeState.isIncognitoActive || BrowserPreferences.get(activity).adFree) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }

        container.post {
            if (activity.isFinishing || activity.isDestroyed) return@post

            val displayMetrics = activity.resources.displayMetrics
            val containerWidthPx = container.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
            val adWidth = (containerWidthPx / displayMetrics.density).toInt()

            if (adWidth <= 0) {
                container.visibility = View.GONE
                return@post
            }

            val adView = AdView(activity).apply {
                adUnitId = activity.getString(R.string.banner_ad_unit_id)
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        activity,
                        adWidth
                    )
                )
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        container.visibility = View.VISIBLE
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        container.removeAllViews()
                        container.visibility = View.GONE
                    }
                }
            }

            container.removeAllViews()
            container.addView(adView)
            container.visibility = View.GONE
            adView.loadAd(AdRequest.Builder().build())
            onBannerCreated(adView)
        }
    }
}
