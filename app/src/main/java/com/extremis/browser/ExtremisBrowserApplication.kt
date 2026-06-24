package com.extremis.browser

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd

class ExtremisBrowserApplication : Application(), Application.ActivityLifecycleCallbacks,
    DefaultLifecycleObserver {

    private var currentActivity: Activity? = null
    private val appOpenAdManager = AppOpenAdManager()

    override fun onCreate() {
        super<Application>.onCreate()
        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        MobileAds.initialize(this) {
            appOpenAdManager.loadAd(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        currentActivity?.let { appOpenAdManager.showAdIfAvailable(it) }
    }

    override fun onActivityStarted(activity: Activity) {
        if (!appOpenAdManager.isShowingAd) {
            currentActivity = activity
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (!appOpenAdManager.isShowingAd) {
            currentActivity = activity
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) {
            currentActivity = null
        }
    }

    private inner class AppOpenAdManager {
        var isShowingAd = false
            private set

        private var appOpenAd: AppOpenAd? = null
        private var isLoadingAd = false
        private var loadTime = 0L

        fun loadAd(application: Application) {
            if (isLoadingAd || isAdAvailable()) return

            isLoadingAd = true
            AppOpenAd.load(
                application,
                AdConfig.APP_OPEN_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        appOpenAd = ad
                        isLoadingAd = false
                        loadTime = System.currentTimeMillis()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        isLoadingAd = false
                    }
                }
            )
        }

        fun showAdIfAvailable(activity: Activity) {
            if (isShowingAd) return

            val ad = appOpenAd
            if (ad == null || !isAdAvailable()) {
                loadAd(this@ExtremisBrowserApplication)
                return
            }

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    appOpenAd = null
                    isShowingAd = false
                    loadAd(this@ExtremisBrowserApplication)
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    appOpenAd = null
                    isShowingAd = false
                    loadAd(this@ExtremisBrowserApplication)
                }

                override fun onAdShowedFullScreenContent() {
                    appOpenAd = null
                    isShowingAd = true
                }
            }
            ad.show(activity)
        }

        private fun isAdAvailable(): Boolean {
            return appOpenAd != null && wasLoadTimeLessThanFourHoursAgo()
        }

        private fun wasLoadTimeLessThanFourHoursAgo(): Boolean {
            val elapsedMillis = System.currentTimeMillis() - loadTime
            return elapsedMillis < AdConfig.APP_OPEN_AD_EXPIRATION_MILLIS
        }
    }
}
