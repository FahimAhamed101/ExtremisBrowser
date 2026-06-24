package com.extremis.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.GridLayoutManager
import com.extremis.browser.adapters.BrowserTabsAdapter
import com.extremis.browser.databinding.ActivityMainBinding
import com.extremis.browser.databinding.ItemNativeAdBinding
import com.extremis.browser.models.Bookmark
import com.extremis.browser.models.BrowserTab
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.utils.DatabaseHelper
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var tabsAdapter: BrowserTabsAdapter

    private val tabs = mutableListOf<BrowserTab>()
    private val homeUrl = BrowserTab.NEW_TAB_URL
    private var currentTabIndex = 0
    private var currentPageTitle = ""
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false
    private var bannerAdView: AdView? = null
    private var nativeAd: NativeAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)
        tabs.add(BrowserTab())

        setupWebView()
        setupTabs()
        setupUi()
        setupHomeContent()
        setupAds()

        val intentUrl = intent?.data?.toString()
        if (intentUrl != null) {
            loadUrl(intentUrl)
        } else {
            showHome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.tabSwitcherPanel.visibility == View.VISIBLE -> hideTabSwitcher()
                    binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    binding.webView.visibility == View.VISIBLE -> loadHomeInCurrentTab()
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()?.let { loadUrl(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url ?: return false
                return if (target.scheme == "http" || target.scheme == "https") {
                    false
                } else {
                    openExternalIntent(target)
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.show()
                updateCurrentTab(url.orEmpty(), currentPageTitle)
                binding.urlInput.setText(url.orEmpty())
                showWeb()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.hide()
                binding.swipeRefresh.isRefreshing = false
                val finishedUrl = url.orEmpty()
                binding.urlInput.setText(finishedUrl)
                updateCurrentTab(finishedUrl, currentPageTitle.ifBlank { finishedUrl })
                refreshTabUi()

                if (!isCurrentTabIncognito() && finishedUrl.isNotBlank() && !finishedUrl.startsWith("about:")) {
                    val title = currentPageTitle.ifBlank { finishedUrl }
                    databaseHelper.addToHistory(HistoryItem(title = title, url = finishedUrl))
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) binding.progressBar.hide() else binding.progressBar.show()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                currentPageTitle = title.orEmpty()
                updateCurrentTab(view?.url.orEmpty(), currentPageTitle)
                refreshTabUi()
            }
        }

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupUi() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.chrome_page_bg)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.chrome_page_bg)

        binding.homeButton.setOnClickListener { loadHomeInCurrentTab() }
        binding.reloadButton.setOnClickListener {
            if (binding.webView.visibility == View.VISIBLE) binding.webView.reload() else showHome()
        }
        binding.menuButton.setOnClickListener { showBrowserMenu(it) }
        binding.tabSwitcherMenuButton.setOnClickListener { showBrowserMenu(it) }
        binding.profileButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.tabCountButton.setOnClickListener { showTabSwitcher() }
        binding.newTabButton.setOnClickListener { addNewTab(false) }
        binding.voiceButton.setOnClickListener {
            Toast.makeText(this, "Voice search is not available", Toast.LENGTH_SHORT).show()
        }
        binding.lensButton.setOnClickListener {
            Toast.makeText(this, "Image search is not available", Toast.LENGTH_SHORT).show()
        }
        binding.continueCard.setOnClickListener {
            loadUrl("https://www.google.com/search?q=browser+privacy+tips")
        }
        binding.seeMoreButton.setOnClickListener { showTabSwitcher() }
        binding.discoverPrimary.setOnClickListener {
            loadUrl("https://www.google.com/search?q=browser+privacy+and+security")
        }

        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            submitInput(binding.urlInput.text?.toString(), actionId, event)
        }
        binding.homeSearchInput.setOnEditorActionListener { _, actionId, event ->
            submitInput(binding.homeSearchInput.text?.toString(), actionId, event)
        }
        binding.tabSearchInput.setOnEditorActionListener { _, _, _ -> false }
        binding.tabSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshTabUi()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.swipeRefresh.setOnRefreshListener {
            if (binding.webView.visibility == View.VISIBLE) {
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.chrome_blue)

        refreshTabUi()
    }

    private fun setupTabs() {
        tabsAdapter = BrowserTabsAdapter(
            onOpen = { index ->
                if (index in tabs.indices) {
                    currentTabIndex = index
                    openCurrentTab()
                }
            },
            onClose = { index -> closeTab(index) }
        )
        binding.tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        binding.tabsRecycler.adapter = tabsAdapter
    }

    private fun setupAds() {
        runCatching {
            MobileAds.initialize(this) {
                loadInterstitialAd()
                loadBannerAd()
                loadNativeAd()
            }
        }.onFailure {
            binding.adViewContainer.visibility = View.GONE
            binding.nativeAdContainer.visibility = View.GONE
        }
    }

    private fun loadBannerAd() {
        binding.adViewContainer.post {
            val adWidth = getBannerAdWidth()
            if (adWidth <= 0) return@post

            val adView = AdView(this).apply {
                adUnitId = AdConfig.BANNER_AD_UNIT_ID
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        this@MainActivity,
                        adWidth
                    )
                )
            }

            bannerAdView?.destroy()
            bannerAdView = adView
            binding.adViewContainer.removeAllViews()
            binding.adViewContainer.addView(adView)
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    private fun getBannerAdWidth(): Int {
        val displayMetrics = resources.displayMetrics
        val containerWidthPx = binding.adViewContainer.width.takeIf { it > 0 }
            ?: displayMetrics.widthPixels
        return (containerWidthPx / displayMetrics.density).toInt()
    }

    private fun loadInterstitialAd() {
        if (interstitialLoading || interstitialAd != null) return
        interstitialLoading = true
        InterstitialAd.load(
            this,
            AdConfig.INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialAd = ad
                    interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            loadInterstitialAd()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            interstitialAd = null
                            loadInterstitialAd()
                        }

                        override fun onAdShowedFullScreenContent() {
                            interstitialAd = null
                        }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    interstitialAd = null
                }
            }
        )
    }

    private fun showInterstitialIfReady() {
        val ad = interstitialAd
        if (ad == null) {
            loadInterstitialAd()
            return
        }
        ad.show(this)
    }

    private fun loadNativeAd() {
        binding.nativeAdContainer.visibility = View.GONE

        val adLoader = AdLoader.Builder(this, AdConfig.NATIVE_AD_UNIT_ID)
            .forNativeAd { loadedNativeAd ->
                if (isDestroyed || isFinishing) {
                    loadedNativeAd.destroy()
                    return@forNativeAd
                }

                nativeAd?.destroy()
                nativeAd = loadedNativeAd

                val nativeAdBinding = ItemNativeAdBinding.inflate(layoutInflater)
                populateNativeAdView(loadedNativeAd, nativeAdBinding)
                binding.nativeAdContainer.removeAllViews()
                binding.nativeAdContainer.addView(nativeAdBinding.root)
                binding.nativeAdContainer.visibility = View.VISIBLE
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    binding.nativeAdContainer.removeAllViews()
                    binding.nativeAdContainer.visibility = View.GONE
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build()
            )
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    private fun populateNativeAdView(ad: NativeAd, nativeAdBinding: ItemNativeAdBinding) {
        val adView = nativeAdBinding.nativeAdView

        adView.mediaView = nativeAdBinding.adMedia
        adView.headlineView = nativeAdBinding.adHeadline
        adView.bodyView = nativeAdBinding.adBody
        adView.callToActionView = nativeAdBinding.adCallToAction
        adView.iconView = nativeAdBinding.adAppIcon
        adView.advertiserView = nativeAdBinding.adAdvertiser

        nativeAdBinding.adHeadline.text = ad.headline
        nativeAdBinding.adMedia.mediaContent = ad.mediaContent
        nativeAdBinding.adMedia.setImageScaleType(ImageView.ScaleType.CENTER_CROP)

        val body = ad.body
        nativeAdBinding.adBody.visibility = if (body.isNullOrBlank()) View.GONE else View.VISIBLE
        nativeAdBinding.adBody.text = body.orEmpty()

        val callToAction = ad.callToAction
        nativeAdBinding.adCallToAction.visibility =
            if (callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        nativeAdBinding.adCallToAction.text = callToAction.orEmpty()

        val icon = ad.icon
        nativeAdBinding.adAppIcon.visibility = if (icon?.drawable == null) View.GONE else View.VISIBLE
        nativeAdBinding.adAppIcon.setImageDrawable(icon?.drawable)

        val advertiser = ad.advertiser
        nativeAdBinding.adAdvertiser.visibility =
            if (advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
        nativeAdBinding.adAdvertiser.text = advertiser.orEmpty()

        adView.setNativeAd(ad)
    }

    private fun setupHomeContent() {
        val quickLinks = listOf(
            QuickLink("E", "Extremis", "https://extremis.top/"),
            QuickLink("S", "Search", "https://www.google.com/"),
            QuickLink("N", "News", "https://www.google.com/search?q=latest+news"),
            QuickLink("+", "Add", null, isAddTile = true)
        )

        binding.quickLinksContainer.removeAllViews()
        quickLinks.forEach { link ->
            binding.quickLinksContainer.addView(createQuickLinkView(link))
        }
    }

    private fun createQuickLinkView(link: QuickLink): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                if (link.isAddTile) {
                    openAddBookmarkShortcut()
                } else {
                    link.url?.let(::loadUrl)
                }
            }
        }
        val iconSize = dp(56)
        val icon = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chrome_quick_icon_bg)
            text = link.initial
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (link.isAddTile) R.color.extremis_blue_dark else R.color.chrome_blue
                )
            )
            textSize = if (link.isAddTile) 24f else 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val label = TextView(this).apply {
            text = link.title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chrome_text))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        item.addView(icon)
        item.addView(label)
        return item
    }

    private fun openAddBookmarkShortcut() {
        val url = currentTab().url
        if (url == homeUrl || url.isBlank()) {
            startActivity(Intent(this, BookmarksActivity::class.java))
            return
        }
        saveCurrentBookmark()
    }

    private fun submitInput(rawText: String?, actionId: Int, event: KeyEvent?): Boolean {
        val shouldGo = actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_SEARCH ||
            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        if (!shouldGo) return false
        val rawInput = rawText?.trim().orEmpty()
        if (rawInput.isNotEmpty()) loadUrl(normalizeUrl(rawInput))
        return true
    }

    private fun normalizeUrl(rawInput: String): String {
        return when {
            rawInput.startsWith("http://", ignoreCase = true) ||
                rawInput.startsWith("https://", ignoreCase = true) -> rawInput

            rawInput.contains(".") && !rawInput.contains(" ") -> "https://$rawInput"
            else -> "https://www.google.com/search?q=${Uri.encode(rawInput)}"
        }
    }

    private fun loadUrl(url: String) {
        val target = if (url == homeUrl) homeUrl else normalizeUrl(url)
        if (target == homeUrl) {
            loadHomeInCurrentTab()
            return
        }
        updateCurrentTab(target, target)
        applyPrivacyMode()
        binding.webView.loadUrl(target)
        hideTabSwitcher()
    }

    private fun loadHomeInCurrentTab() {
        currentTab().url = homeUrl
        currentTab().title = getString(if (currentTab().incognito) R.string.new_incognito_tab else R.string.new_tab)
        currentPageTitle = ""
        applyPrivacyMode()
        binding.homeSearchInput.setText("")
        binding.urlInput.setText("")
        showHome()
        refreshTabUi()
    }

    private fun showHome() {
        applyCurrentTabTheme()
        binding.homeScroll.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.GONE
        binding.compactUrlBar.visibility = View.VISIBLE
        binding.homeTopSpacer.visibility = View.GONE
        binding.progressBar.hide()
        binding.incognitoLabel.visibility = if (isCurrentTabIncognito()) View.VISIBLE else View.GONE
        binding.googleLogo.visibility = View.VISIBLE
    }

    private fun showWeb() {
        applyCurrentTabTheme()
        binding.homeScroll.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.compactUrlBar.visibility = View.VISIBLE
        binding.homeTopSpacer.visibility = View.GONE
    }

    private fun showTabSwitcher() {
        refreshTabUi()
        binding.tabSwitcherPanel.visibility = View.VISIBLE
    }

    private fun applyCurrentTabTheme() {
        if (isCurrentTabIncognito()) {
            applyIncognitoTheme()
        } else {
            applyNormalTheme()
        }
    }

    private fun applyNormalTheme() {
        val page = ContextCompat.getColor(this, R.color.chrome_page_bg)
        val text = ContextCompat.getColor(this, R.color.chrome_text)
        val muted = ContextCompat.getColor(this, R.color.chrome_muted)

        binding.root.setBackgroundColor(page)
        binding.browserContainer.setBackgroundColor(page)
        binding.contentFrame.setBackgroundColor(page)
        binding.homeScroll.setBackgroundColor(page)
        binding.homeContent.setBackgroundColor(page)
        binding.tabSwitcherPanel.setBackgroundColor(page)
        binding.adViewContainer.setBackgroundColor(page)
        window.statusBarColor = page
        window.navigationBarColor = page

        binding.urlInput.setTextColor(text)
        binding.urlInput.setHintTextColor(muted)
        binding.homeSearchInput.setTextColor(text)
        binding.homeSearchInput.setHintTextColor(text)
        binding.googleLogo.setTextColor(ContextCompat.getColor(this, R.color.extremis_blue))
        binding.incognitoLabel.setTextColor(muted)
        binding.tabCountButton.setTextColor(text)
        binding.tabSwitcherCount.setTextColor(ContextCompat.getColor(this, R.color.chrome_blue))
    }

    private fun applyIncognitoTheme() {
        val page = ContextCompat.getColor(this, R.color.incognito_page_bg)
        val text = ContextCompat.getColor(this, R.color.incognito_text)
        val muted = ContextCompat.getColor(this, R.color.incognito_muted)

        binding.root.setBackgroundColor(page)
        binding.browserContainer.setBackgroundColor(page)
        binding.contentFrame.setBackgroundColor(page)
        binding.homeScroll.setBackgroundColor(page)
        binding.homeContent.setBackgroundColor(page)
        binding.tabSwitcherPanel.setBackgroundColor(page)
        binding.adViewContainer.setBackgroundColor(page)
        window.statusBarColor = page
        window.navigationBarColor = page

        binding.urlInput.setTextColor(text)
        binding.urlInput.setHintTextColor(muted)
        binding.homeSearchInput.setTextColor(text)
        binding.homeSearchInput.setHintTextColor(muted)
        binding.googleLogo.setTextColor(text)
        binding.incognitoLabel.setTextColor(text)
        binding.tabCountButton.setTextColor(text)
        binding.tabSwitcherCount.setTextColor(text)
    }

    private fun hideTabSwitcher() {
        binding.tabSwitcherPanel.visibility = View.GONE
    }

    private fun addNewTab(incognito: Boolean) {
        tabs.add(
            BrowserTab(
                title = getString(if (incognito) R.string.new_incognito_tab else R.string.new_tab),
                incognito = incognito
            )
        )
        currentTabIndex = tabs.lastIndex
        loadHomeInCurrentTab()
        hideTabSwitcher()
        showInterstitialIfReady()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val closingIncognito = tabs[index].incognito
        tabs.removeAt(index)
        if (closingIncognito) clearPrivateWebData()
        if (tabs.isEmpty()) {
            tabs.add(BrowserTab())
            currentTabIndex = 0
        } else if (currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.lastIndex
        } else if (index < currentTabIndex) {
            currentTabIndex -= 1
        }
        openCurrentTab()
        showTabSwitcher()
    }

    private fun openCurrentTab() {
        val tab = currentTab()
        currentPageTitle = tab.title
        applyPrivacyMode()
        refreshTabUi()
        if (tab.url == homeUrl) {
            showHome()
        } else {
            binding.webView.loadUrl(tab.url)
        }
        hideTabSwitcher()
    }

    private fun updateCurrentTab(url: String, title: String) {
        val tab = currentTab()
        if (url.isNotBlank()) tab.url = url
        if (title.isNotBlank() && !title.startsWith("http", ignoreCase = true)) {
            tab.title = title
        } else if (url.isNotBlank()) {
            tab.title = url.toUri().host?.removePrefix("www.") ?: url
        }
    }

    private fun refreshTabUi() {
        val tabCount = tabs.size.coerceAtLeast(1).toString()
        binding.tabCountButton.text = tabCount
        binding.tabCountButton.background = ContextCompat.getDrawable(this, R.drawable.chrome_tab_count_bg)
        binding.tabSwitcherCount.text = tabCount
        binding.tabSwitcherCount.background = ContextCompat.getDrawable(this, R.drawable.chrome_tab_count_bg)
        tabsAdapter.submitTabs(tabs, currentTabIndex, binding.tabSearchInput.text?.toString().orEmpty())
    }

    private fun showBrowserMenu(anchor: View) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            background = roundedMenuBackground()
        }

        content.addView(createMenuIconRow())
        content.addView(createBrowserHeaderRow())
        content.addView(createMenuRow(R.drawable.ic_add_24, getString(R.string.new_tab)) { addNewTab(false) })
        content.addView(createMenuRow(R.drawable.ic_incognito_24, getString(R.string.new_incognito_tab)) { addNewTab(true) })
        content.addView(createDivider())
        content.addView(createMenuRow(R.drawable.ic_history_24, getString(R.string.history)) {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        content.addView(createMenuRow(R.drawable.ic_delete_24, getString(R.string.delete_browsing_data)) {
            clearBrowsingData()
        })
        content.addView(createDivider())
        content.addView(createMenuRow(R.drawable.ic_download_24, getString(R.string.downloads)) {
            startActivity(Intent(this, DownloadsActivity::class.java))
        })
        content.addView(createMenuRow(R.drawable.ic_bookmark_24, getString(R.string.bookmarks)) {
            startActivity(Intent(this, BookmarksActivity::class.java))
        })
        content.addView(createMenuRow(R.drawable.ic_history_24, getString(R.string.recent_tabs)) {
            showTabSwitcher()
        })
        content.addView(createDivider())
        content.addView(createMenuRow(R.drawable.ic_settings_24, getString(R.string.settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        content.addView(createMenuRow(R.drawable.ic_help_24, getString(R.string.help_feedback)) {
            Toast.makeText(this, R.string.app_name, Toast.LENGTH_SHORT).show()
        })

        val popup = PopupWindow(content, dp(264), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        popup.showAsDropDown(anchor, -dp(248), -dp(4))
    }

    private fun createMenuIconRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        val items = listOf(
            R.drawable.ic_arrow_forward_24 to { if (binding.webView.canGoForward()) binding.webView.goForward() },
            R.drawable.ic_star_24 to { saveCurrentBookmark() },
            R.drawable.ic_download_24 to { startActivity(Intent(this, DownloadsActivity::class.java)) },
            R.drawable.ic_info_24 to { Toast.makeText(this, currentTab().url, Toast.LENGTH_SHORT).show() },
            R.drawable.ic_reload_24 to { if (binding.webView.visibility == View.VISIBLE) binding.webView.reload() }
        )
        items.forEach { (iconRes, action) ->
            row.addView(ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
                setImageResource(iconRes)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { action() }
            })
        }
        return row
    }

    private fun createBrowserHeaderRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.app_name)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.extremis_blue))
                    textSize = 14f
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Browser tools and privacy controls"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chrome_muted))
                    textSize = 12f
                })
            })
            addView(ImageButton(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
                setImageResource(R.drawable.ic_arrow_forward_24)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.extremis_blue))
            })
        }
    }

    private fun createMenuRow(iconRes: Int, label: String, action: () -> Unit): View {
        return TextView(this).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chrome_text))
            val icon = ContextCompat.getDrawable(this@MainActivity, iconRes)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(16)
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
            setOnClickListener { action() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            setBackgroundColor(android.graphics.Color.rgb(232, 232, 240))
        }
    }

    private fun roundedMenuBackground(): GradientDrawable {
        return GradientDrawable().apply {
            color = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.chrome_card_bg)
            )
            cornerRadius = dp(6).toFloat()
        }
    }

    private fun saveCurrentBookmark() {
        val url = currentTab().url
        if (url == homeUrl || url.isBlank()) {
            Toast.makeText(this, "Open a page first to bookmark it", Toast.LENGTH_SHORT).show()
            return
        }
        databaseHelper.addBookmark(Bookmark(title = currentTab().title, url = url))
        Toast.makeText(this, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
    }

    private fun clearBrowsingData() {
        databaseHelper.clearHistory()
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        Toast.makeText(this, R.string.browsing_data_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun applyPrivacyMode() {
        binding.webView.settings.cacheMode = if (isCurrentTabIncognito()) {
            WebSettings.LOAD_NO_CACHE
        } else {
            WebSettings.LOAD_DEFAULT
        }
        if (isCurrentTabIncognito()) {
            binding.webView.clearCache(false)
        }
    }

    private fun clearPrivateWebData() {
        binding.webView.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeSessionCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition.orEmpty(), mimeType.orEmpty())
            val request = DownloadManager.Request(url.toUri()).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { cookies ->
                    addRequestHeader("Cookie", cookies)
                }
                setTitle(fileName)
                setDescription("Downloading $fileName")
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Download failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternalIntent(uri: Uri) {
        val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(externalIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentTab(): BrowserTab = tabs[currentTabIndex.coerceIn(tabs.indices)]

    private fun isCurrentTabIncognito(): Boolean = currentTab().incognito

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
        refreshTabUi()
    }

    override fun onPause() {
        bannerAdView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        bannerAdView?.destroy()
        bannerAdView = null
        nativeAd?.destroy()
        nativeAd = null
        binding.webView.apply {
            clearHistory()
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private data class QuickLink(
        val initial: String,
        val title: String,
        val url: String?,
        val isAddTile: Boolean = false
    )

    companion object {
        fun createLaunchIntent(context: Context, url: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                data = Uri.parse(url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}
