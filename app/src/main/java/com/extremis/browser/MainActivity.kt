package com.extremis.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.BrowserTabsAdapter
import com.extremis.browser.adapters.MediaListAdapter
import com.extremis.browser.adapters.SuggestionAdapter
import com.extremis.browser.billing.BillingManager
import com.extremis.browser.databinding.ActivityMainBinding
import com.extremis.browser.databinding.DialogBrowserMenuBinding
import com.extremis.browser.databinding.DialogVideoDownloadBinding
import com.extremis.browser.databinding.ItemNativeAdBinding
import com.extremis.browser.media.MediaCapture
import com.extremis.browser.media.MediaDetector
import com.extremis.browser.media.MediaDownloadService
import com.extremis.browser.media.MediaItem
import com.extremis.browser.media.MediaKind
import com.extremis.browser.media.MediaRegistry
import com.extremis.browser.media.YouTubeExtractor
import com.extremis.browser.models.Bookmark
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.tabs.TabManager
import com.extremis.browser.tabs.TabSession
import com.extremis.browser.utils.AdBlocker
import com.extremis.browser.utils.BrowserPreferences
import com.extremis.browser.utils.DatabaseHelper
import com.extremis.browser.utils.FaviconLoader
import com.extremis.browser.utils.SearchEngine
import com.extremis.browser.utils.SuggestionsProvider
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var tabsAdapter: BrowserTabsAdapter
    private lateinit var tabsLayoutManager: GridLayoutManager
    private lateinit var suggestionAdapter: SuggestionAdapter
    private lateinit var preferences: BrowserPreferences
    private lateinit var tabManager: TabManager

    private val homeUrl = TabSession.NEW_TAB_URL
    private var currentPageTitle = ""
    private var lastAppliedIncognito: Boolean? = null
    private var omniboxFocused = false
    private var browserMenu: BottomSheetDialog? = null

    /** True while the omnibox dropdown is showing autocomplete for the address bar. */
    private var omniboxSuggestionsVisible = false
    private val suggestionsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "suggestions").apply { isDaemon = true }
    }

    /** Set while a `<input type=file>` picker is open, per WebChromeClient.onShowFileChooser. */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** Current find-in-page query, kept so we can re-run it on navigation. */
    private var findQuery: String = ""
    private var findMatches: Int = 0

    /** Host for a fullscreen <video>, handed over by onShowCustomView. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Bottom sheet listing the streams the scanner found on the active page. */
    private var mediaSheet: BottomSheetDialog? = null

    /** Fires on the main thread whenever the scanner learns something new for a tab. */
    private val pageMediaListener: (Long) -> Unit = { tabId -> onPageMediaUpdated(tabId) }

    /** Last clipboard value we already offered to open, so the monitor cannot nag. */
    private var lastClipboardValue: String? = null

    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false
    private var lastInterstitialShowTime = 0L

    /** Pages finished this session, used to space interstitials out. */
    private var pagesFinished = 0
    private var bannerAdView: AdView? = null
    private var nativeAd: NativeAd? = null

    private lateinit var topControlsBasePadding: ViewPadding
    private lateinit var homeContentBasePadding: ViewPadding
    private lateinit var swipeRefreshBasePadding: ViewPadding
    private lateinit var adContainerBasePadding: ViewPadding
    private lateinit var tabSwitcherBasePadding: ViewPadding

    /**
     * Voice search. Chrome puts a microphone in the omnibox; this wires it to the platform
     * speech recogniser and drops the transcript straight into the address bar.
     */
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (result.resultCode == RESULT_OK && spoken.isNotBlank()) {
            binding.urlInput.setText(spoken)
            loadUrl(normalizeUrl(spoken))
        }
    }

    /**
     * Result of the `<input type=file>` picker. KoiBrowser handled this in `onActivityResult`;
     * we use the contract API so the callback survives process death.
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
            }

            result.data?.data != null -> arrayOf(result.data!!.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)
        preferences = BrowserPreferences.get(this)
        preferences.registerLaunch()

        // Start parsing the ~45k-entry hosts list now; isAd() stays a no-op until it lands.
        AdBlocker.updateEnabled(preferences.adBlockEnabled)
        AdBlocker.ensureLoaded(this)

        MediaCapture.init(this)
        // The download button has to appear as soon as a video starts playing, not only when a
        // URL has been scanned — on YouTube a URL is never scanned.
        MediaCapture.onCaptureChanged = {
            runOnUiThread { refreshVideoDownloadButton() }
        }
        // A full download drives the page's own player and reports back through these two.
        MediaCapture.onFullCaptureProgress = { percent ->
            runOnUiThread { updateCaptureProgress(percent) }
        }
        MediaCapture.onFullCaptureReady = { reason ->
            android.util.Log.i(LOG_TAG, "full capture ready: $reason")
            runOnUiThread {
                // Only a drive this screen started may finish a save. A late report from a
                // previous run would otherwise publish a fragment over the finished download.
                if (captureDialog?.isShowing == true) finishCapturedSave()
            }
        }
        MediaRegistry.addListener(pageMediaListener)

        setupWindowInsets()
        setupTabManager()
        setupTabs()
        setupUi()
        setupFindBar()
        setupAds()

        val intentUrl = intent?.data?.toString()
        if (intentUrl != null) {
            openInNewTab(intentUrl, incognito = false)
        } else {
            restoreOrCreateFirstTab()
        }

        maybePromptForRating()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = activeWebView
                when {
                    binding.fullscreenContainer.visibility == View.VISIBLE ->
                        hideCustomView()

                    binding.suggestionPanel.visibility == View.VISIBLE -> hideSuggestions()
                    binding.tabSwitcherPanel.visibility == View.VISIBLE -> hideTabSwitcher()
                    binding.findBar.visibility == View.VISIBLE -> closeFindInPage()
                    isShowingWeb() && webView?.canGoBack() == true -> {
                        webView.goBack()
                        updateNavigationState()
                    }

                    isShowingWeb() -> loadHomeInCurrentTab()
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()?.let { openInNewTab(it, incognito = false) }
    }

    /**
     * Reopen the previous session when the user has that preference on and there is something to
     * restore (KoiBrowser's `restoreLostTabs`); otherwise start on the New Tab Page.
     */
    private fun restoreOrCreateFirstTab() {
        val restored = preferences.restoreTabs && tabManager.restore(preferences.savedTabs)
        if (!restored) {
            tabManager.newTab(incognito = false, url = homeUrl, activate = true)
            showHome()
        } else {
            val session = tabManager.current
            if (session == null || session.isNewTab) {
                showHome()
            } else {
                loadSession(session)
            }
        }
        refreshTabUi()
    }

    // ==================================================================
    // Tabs and WebView
    // ==================================================================

    /** The WebView of whichever tab is on screen, or null before the first tab exists. */
    private val activeWebView: WebView?
        get() = tabManager.current?.let { if (it.hasWebView) it.webView else null }

    private fun setupTabManager() {
        tabManager = TabManager(binding.webHost) { session -> createConfiguredWebView(session) }
        tabManager.onTabCountChanged = { refreshTabUi() }
        tabManager.onCurrentTabChanged = { session ->
            currentPageTitle = session.title
            applyPrivacyMode()
            refreshTabUi()
            updateNavigationState()
            // Each tab has its own detections, so the icon has to follow the active tab.
            refreshVideoDownloadButton()
        }
    }

    /**
     * Build and fully configure the WebView for one tab.
     *
     * Each tab gets its own client instances that capture [session], so a background tab's
     * callbacks can never be mistaken for the foreground tab's — the failure mode KoiBrowser hit
     * by routing every tab through one shared client and comparing WebView identity.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(session: TabSession): WebView {
        val webView = WebView(this)

        val settings = webView.settings
        settings.javaScriptEnabled = preferences.javaScriptEnabled
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.loadsImagesAutomatically = preferences.imagesEnabled
        settings.blockNetworkImage = !preferences.imagesEnabled
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = if (session.incognito) {
            WebSettings.LOAD_NO_CACHE
        } else {
            WebSettings.LOAD_DEFAULT
        }
        settings.userAgentString = if (preferences.desktopMode) DESKTOP_USER_AGENT else null

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !session.incognito)

        // The page scanner reports what it finds through this bridge. Only @JavascriptInterface
        // methods are reachable from page script, and both of ours take nothing but strings, so a
        // hostile page cannot use the bridge to touch anything else in the app.
        webView.addJavascriptInterface(MediaBridge(session.id), JS_BRIDGE_NAME)

        // Must come after the bridge (the hook posts through it) and before the page loads.
        installDocumentStartCapture(webView)

        webView.webViewClient = object : WebViewClient() {
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

            /**
             * Ad blocking, ported from KoiBrowser's `AdBlock.isAd` hook. KoiBrowser called it from
             * `WebClient.shouldInterceptRequest`; we return an empty response for blocked hosts.
             *
             * This runs on a WebView background thread, where an escaping exception kills the whole
             * process, so the whole body is guarded.
             *
             * Note: do NOT reach for `InputStream.nullInputStream()` here. It is a Java 11 API that
             * only landed on Android 13 (API 33), so it compiles against compileSdk 36 but throws
             * `NoSuchMethodError` at runtime on anything older — which is exactly what crashed video
             * playback on API 30.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return try {
                    val url = request?.url?.toString()
                    if (url != null) {
                        // The only place that sees every request a page makes, including the XHR
                        // and fetch calls that feed MediaSource. Players that never expose a
                        // usable src attribute — YouTube, most HLS sites — are only catchable here.
                        MediaRegistry.offer(session.id, url, session.title)
                    }
                    if (url != null && AdBlocker.isAd(url)) {
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    } else {
                        null
                    }
                } catch (error: Exception) {
                    android.util.Log.w(
                        "AdBlocker",
                        "Blocked-request intercept failed for ${request?.url}",
                        error
                    )
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                session.url = url.orEmpty()
                session.hasLoadedPage = true

                // A new document starts a new capture. Clearing here rather than on page finish
                // matters: the player buffers the whole clip while the document is still loading,
                // so clearing on finish would throw away bytes that arrived a moment earlier.
                // Only the visible tab may reset it — capture is shared, and a background tab
                // navigating must not wipe what the user is watching.
                if (isActiveSession(session)) MediaCapture.clear()

                if (!isActiveSession(session)) return
                binding.progressBar.show()
                binding.urlInput.setText(url.orEmpty())
                showWeb()
                updateNavigationState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val finishedUrl = url.orEmpty()
                session.url = finishedUrl
                if (session.title.isBlank()) {
                    session.title = finishedUrl.toUri().host?.removePrefix("www.") ?: finishedUrl
                }

                // A new document means the previous page's streams are gone. Same-URL reloads keep
                // what we already found, so a refresh does not empty the sheet.
                if (session.lastScannedUrl != finishedUrl) {
                    session.lastScannedUrl = finishedUrl
                    MediaRegistry.clear(session.id)
                }
                injectPageMedia(view)

                if (!isActiveSession(session)) return

                binding.progressBar.hide()
                binding.swipeRefresh.isRefreshing = false
                binding.urlInput.setText(finishedUrl)
                currentPageTitle = session.title
                refreshTabUi()
                updateNavigationState()

                // Re-apply an in-progress find so the highlight survives navigation.
                if (binding.findBar.visibility == View.VISIBLE && findQuery.isNotEmpty()) {
                    view?.findAllAsync(findQuery)
                }

                if (!session.incognito && finishedUrl.isNotBlank() &&
                    !finishedUrl.startsWith("about:")
                ) {
                    databaseHelper.addToHistory(
                        HistoryItem(title = session.title.ifBlank { finishedUrl }, url = finishedUrl)
                    )
                }

                maybeShowInterstitial(finishedUrl)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!isActiveSession(session)) return
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) binding.progressBar.hide() else binding.progressBar.show()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                session.title = title.orEmpty()
                if (!isActiveSession(session)) return
                currentPageTitle = session.title
                refreshTabUi()
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                FaviconLoader.put(session.url, icon)
            }

            /** Pop-up blocking — KoiBrowser's `block_popups` preference. */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (preferences.blockPopups) {
                    Toast.makeText(
                        this@MainActivity, R.string.popup_blocked, Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = createConfiguredWebView(
                    TabSession(
                        id = System.nanoTime(),
                        incognito = session.incognito,
                        url = "",
                        title = ""
                    )
                )
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            /** `<input type=file>` support — KoiBrowser's `showFileChooser`. */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(
                        fileChooserParams?.createIntent()
                            ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    )
                    true
                } catch (_: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            /** Fullscreen <video> — KoiBrowser's `onShowCustomView` / `onHideCustomView`. */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                // Chromium can hand over a view that is still attached to its own hierarchy.
                // Calling addView() without detaching first throws
                // "The specified child already has a parent" and takes the process down.
                (view.parent as? ViewGroup)?.removeView(view)
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.browserContainer.visibility = View.GONE
                binding.adViewContainer.visibility = View.GONE
                setFullscreenSystemBars(true)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.setOnLongClickListener { handleLongPress(webView) }
        attachFindListener(webView)

        return webView
    }

    /**
     * Leave fullscreen video.
     *
     * Idempotent on purpose: the WebView can call `onHideCustomView()` after we already tore the
     * surface down ourselves (back press, tab close, activity teardown), and the state is captured
     * before any view is touched so a re-entry cannot act on a stale callback.
     */
    private fun hideCustomView() {
        val callback = customViewCallback
        customView = null
        customViewCallback = null

        binding.fullscreenContainer.removeAllViews()
        binding.fullscreenContainer.visibility = View.GONE
        binding.browserContainer.visibility = View.VISIBLE

        // During teardown the window is already going away; touching the insets controller or the
        // ad views from here is pointless and can throw.
        if (!isFinishing && !isDestroyed) {
            setFullscreenSystemBars(false)
            // Note: do NOT call webChromeClient.onHideCustomView() here — that is the callback the
            // WebView invokes, and re-entering it would recurse straight back into this method.
            callback?.onCustomViewHidden()
            refreshAdVisibility()
        }
    }

    private fun setFullscreenSystemBars(fullscreen: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun isActiveSession(session: TabSession): Boolean = tabManager.current === session

    // ==================================================================
    // Chrome toolbar + New Tab Page wiring
    // ==================================================================

    private fun setupUi() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.chrome_page_bg)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.chrome_page_bg)

        binding.backButton.setOnClickListener {
            activeWebView?.let { webView ->
                if (webView.canGoBack()) {
                    webView.goBack()
                    updateNavigationState()
                }
            }
        }
        binding.securityIcon.setOnClickListener { showPageInfo() }
        binding.menuButton.setOnClickListener { showBrowserMenu() }
        binding.tabSwitcherMenuButton.setOnClickListener { showBrowserMenu() }
        binding.tabCountButton.setOnClickListener { showTabSwitcher() }
        binding.newTabButton.setOnClickListener { addNewTab(false) }
        binding.omniVoiceButton.setOnClickListener { startVoiceSearch() }
        binding.omniLensButton.setOnClickListener { startImageSearch() }
        binding.voiceButton.setOnClickListener { startVoiceSearch() }
        binding.lensButton.setOnClickListener { startImageSearch() }
        binding.videoDownloadButton.setOnClickListener { showMediaSheet() }
        binding.seeMoreButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            submitInput(binding.urlInput.text?.toString(), actionId, event)
        }
        binding.urlInput.setOnFocusChangeListener { _, hasFocus ->
            omniboxFocused = hasFocus
            updateOmniboxBackground()
            if (hasFocus) {
                binding.urlInput.post { binding.urlInput.selectAll() }
                requestSuggestions(binding.urlInput.text?.toString().orEmpty())
            } else {
                hideSuggestions()
            }
        }
        binding.urlInput.setOnClickListener {
            binding.urlInput.post { binding.urlInput.selectAll() }
        }
        // Live autocomplete, debounced so we do not hammer the suggestion endpoint per keystroke.
        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (omniboxFocused) requestSuggestions(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.homeSearchInput.setOnEditorActionListener { _, actionId, event ->
            submitInput(binding.homeSearchInput.text?.toString(), actionId, event)
        }

        binding.tabSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshTabUi()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        // SwipeRefreshLayout decides whether a pull-to-refresh is allowed by asking its child
        // whether it can scroll up. Its only child here is a FrameLayout wrapping the WebView, and
        // a FrameLayout never reports itself as scrollable — so without this callback every
        // downward drag anywhere on the page was treated as a refresh and reloaded the page.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val webView = activeWebView
            isShowingWeb() && webView != null && webView.canScrollVertically(-1)
        }
        // Require a deliberate pull. An accidental short drag mid-page must never reload.
        binding.swipeRefresh.setDistanceToTriggerSync(
            (resources.displayMetrics.density * 88).toInt()
        )
        binding.swipeRefresh.setOnRefreshListener {
            val webView = activeWebView
            if (isShowingWeb() && webView != null) {
                webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.chrome_blue)

        refreshTabUi()
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        topControlsBasePadding = binding.topControls.capturePadding()
        homeContentBasePadding = binding.homeContent.capturePadding()
        swipeRefreshBasePadding = binding.swipeRefresh.capturePadding()
        adContainerBasePadding = binding.adViewContainer.capturePadding()
        tabSwitcherBasePadding = binding.tabSwitcherPanel.capturePadding()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.topControls.updatePadding(
                left = topControlsBasePadding.left + systemBars.left,
                top = topControlsBasePadding.top + systemBars.top,
                right = topControlsBasePadding.right + systemBars.right,
                bottom = topControlsBasePadding.bottom
            )
            binding.homeContent.updatePadding(
                left = homeContentBasePadding.left + systemBars.left,
                top = homeContentBasePadding.top,
                right = homeContentBasePadding.right + systemBars.right,
                bottom = homeContentBasePadding.bottom + systemBars.bottom
            )
            binding.swipeRefresh.updatePadding(
                left = swipeRefreshBasePadding.left + systemBars.left,
                top = swipeRefreshBasePadding.top,
                right = swipeRefreshBasePadding.right + systemBars.right,
                bottom = swipeRefreshBasePadding.bottom + systemBars.bottom
            )
            binding.adViewContainer.updatePadding(
                left = adContainerBasePadding.left + systemBars.left,
                top = adContainerBasePadding.top,
                right = adContainerBasePadding.right + systemBars.right,
                bottom = adContainerBasePadding.bottom + systemBars.bottom
            )
            binding.tabSwitcherPanel.updatePadding(
                left = tabSwitcherBasePadding.left + systemBars.left,
                top = tabSwitcherBasePadding.top + systemBars.top,
                right = tabSwitcherBasePadding.right + systemBars.right,
                bottom = tabSwitcherBasePadding.bottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupTabs() {
        tabsAdapter = BrowserTabsAdapter(
            onOpen = { index ->
                tabManager.select(index)
                onTabSelected()
            },
            onClose = { index -> closeTab(index) }
        )
        tabsLayoutManager = GridLayoutManager(this, 2)
        binding.tabsRecycler.layoutManager = tabsLayoutManager
        binding.tabsRecycler.adapter = tabsAdapter
        binding.tabsRecycler.doOnLayout { updateTabGridSpanCount() }

        suggestionAdapter = SuggestionAdapter { suggestion ->
            hideSuggestions()
            binding.urlInput.setText(suggestion)
            binding.urlInput.clearFocus()
            loadUrl(normalizeUrl(suggestion))
        }
        binding.suggestionList.layoutManager = LinearLayoutManager(this)
        binding.suggestionList.adapter = suggestionAdapter
    }

    /** Wire the find-in-page bar ported from KoiBrowser's `find_on_page.xml`. */
    private fun setupFindBar() {
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                findQuery = s?.toString().orEmpty()
                activeWebView?.findAllAsync(findQuery)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                activeWebView?.findNext(true)
                true
            } else {
                false
            }
        }

        binding.findPrevButton.setOnClickListener { activeWebView?.findNext(false) }
        binding.findNextButton.setOnClickListener { activeWebView?.findNext(true) }
        binding.findCloseButton.setOnClickListener { closeFindInPage() }
    }

    // ==================================================================
    // New Tab Page content
    // ==================================================================

    /**
     * Chrome's "Most visited" row. The first tile is pinned to updates.today, then a couple of
     * built-in shortcuts, then whatever the user has bookmarked, and finally the "Add" tile.
     */
    private fun mostVisitedLinks(): List<QuickLink> {
        val links = mutableListOf(
            QuickLink("updates.today", "https://updates.today/"),
            QuickLink("Search", "https://www.google.com/"),
            QuickLink("News", "https://news.google.com/"),
            QuickLink("YouTube", "https://m.youtube.com/")
        )
        databaseHelper.getBookmarks().take(MAX_MOST_VISITED_BOOKMARKS).forEach { bookmark ->
            val title = bookmark.title.trim().ifBlank { FaviconLoader.hostOf(bookmark.url).orEmpty() }
            if (title.isNotBlank() && links.none { it.url == bookmark.url }) {
                links.add(QuickLink(title, bookmark.url))
            }
        }
        return links
    }

    private fun refreshHomeContent() {
        buildMostVisitedRow()
        buildFeed()
    }

    private fun buildMostVisitedRow() {
        val incognito = isCurrentTabIncognito()
        val container = binding.quickLinksContainer
        container.removeAllViews()

        val gutter = resources.getDimensionPixelSize(R.dimen.chrome_gutter)
        val available = resources.displayMetrics.widthPixels - (gutter * 2)
        val tileWidth = (available / MOST_VISITED_COLUMNS).coerceAtLeast(dp(64))

        mostVisitedLinks().forEach { link ->
            container.addView(createMostVisitedTile(link, tileWidth, incognito))
        }
        container.addView(createAddTile(tileWidth, incognito))
    }

    private fun createMostVisitedTile(
        link: QuickLink,
        tileWidth: Int,
        incognito: Boolean
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            background = rippleBackground()
            setOnClickListener { loadUrl(link.url) }
        }

        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.chrome_most_visited_icon),
                resources.getDimensionPixelSize(R.dimen.chrome_most_visited_icon)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (incognito) R.drawable.incognito_circle_bg else R.drawable.chrome_circle_bg
            )
        }

        val initial = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            text = link.initial
            setTextColor(
                resolveColor(if (incognito) R.color.incognito_muted else R.color.chrome_blue)
            )
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val favicon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        circle.addView(initial)
        circle.addView(favicon)
        if (incognito) {
            // Do not leak a network request out of an incognito tab.
            favicon.setImageResource(R.drawable.ic_incognito_24)
            initial.visibility = View.GONE
        } else {
            FaviconLoader.load(link.url, favicon, fallbackIconRes = 0)
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            text = link.title
            setTextColor(
                resolveColor(if (incognito) R.color.incognito_text else R.color.chrome_text)
            )
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        tile.addView(circle)
        tile.addView(label)
        return tile
    }

    private fun createAddTile(tileWidth: Int, incognito: Boolean): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            background = rippleBackground()
            setOnClickListener { openAddBookmarkShortcut() }
        }

        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.chrome_most_visited_icon),
                resources.getDimensionPixelSize(R.dimen.chrome_most_visited_icon)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (incognito) R.drawable.incognito_circle_bg else R.drawable.chrome_circle_bg
            )
        }
        circle.addView(
            ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.ic_add_24)
                setColorFilter(
                    resolveColor(if (incognito) R.color.incognito_text else R.color.chrome_blue)
                )
            }
        )

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            text = getString(R.string.add_bookmark_shortcut)
            setTextColor(
                resolveColor(if (incognito) R.color.incognito_text else R.color.chrome_text)
            )
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
        }

        tile.addView(circle)
        tile.addView(label)
        return tile
    }

    /**
     * Chrome's Discover feed, backed by the user's real browsing history so the page stays
     * useful instead of showing filler. Falls back to a few tips when history is empty.
     */
    private fun buildFeed() {
        val incognito = isCurrentTabIncognito()
        val container = binding.discoverCard
        container.removeAllViews()

        val history = if (incognito) emptyList() else databaseHelper.getHistory()
        if (history.isEmpty()) {
            fallbackTips().forEach { tip ->
                container.addView(
                    createFeedRow(
                        title = tip.first,
                        url = tip.second,
                        timestamp = 0L,
                        showTime = false,
                        incognito = incognito
                    )
                )
            }
            return
        }

        val seen = mutableSetOf<String>()
        history.filter { seen.add(it.url) }
            .take(MAX_FEED_ITEMS)
            .forEach { item ->
                container.addView(
                    createFeedRow(
                        title = item.title,
                        url = item.url,
                        timestamp = item.timestamp,
                        showTime = true,
                        incognito = incognito
                    )
                )
            }
    }

    private fun createFeedRow(
        title: String,
        url: String,
        timestamp: Long,
        showTime: Boolean,
        incognito: Boolean
    ): View {
        val textColor = resolveColor(if (incognito) R.color.incognito_text else R.color.chrome_text)
        val mutedColor = resolveColor(if (incognito) R.color.incognito_muted else R.color.chrome_muted)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            background = rippleBackground()
            setOnClickListener { loadUrl(url) }
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sourceIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        val sourceLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            text = FaviconLoader.hostOf(url) ?: url
            setTextColor(mutedColor)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        sourceRow.addView(sourceIcon)
        sourceRow.addView(sourceLabel)

        val headline = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            text = title.ifBlank { url }
            setTextColor(textColor)
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        textColumn.addView(sourceRow)
        textColumn.addView(headline)

        if (showTime && timestamp > 0L) {
            textColumn.addView(
                TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                    text = android.text.format.DateUtils.getRelativeTimeSpanString(timestamp)
                    setTextColor(mutedColor)
                    textSize = 12f
                }
            )
        }

        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.chrome_feed_thumb_width),
                resources.getDimensionPixelSize(R.dimen.chrome_feed_thumb_height)
            ).apply { marginStart = dp(14) }
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (incognito) R.drawable.incognito_circle_bg else R.drawable.chrome_thumb_bg
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setColorFilter(mutedColor)
        }

        if (incognito) {
            sourceIcon.setImageResource(R.drawable.ic_incognito_24)
            sourceIcon.setColorFilter(mutedColor)
        } else {
            FaviconLoader.load(url, sourceIcon)
            sourceIcon.clearColorFilter()
            FaviconLoader.load(url, thumb, fallbackIconRes = 0)
        }

        row.addView(textColumn)
        row.addView(thumb)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(createDivider())
        }
    }

    private fun fallbackTips(): List<Pair<String, String>> = listOf(
        "Search smarter with operators" to
            "https://www.google.com/search?q=google+search+operators",
        "Manage your saved passwords" to
            "https://www.google.com/search?q=manage+saved+passwords",
        "Browse privately with Incognito" to
            "https://www.google.com/search?q=browse+privately+incognito"
    )

    private fun createDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
        setBackgroundColor(
            resolveColor(if (isCurrentTabIncognito()) R.color.incognito_outline else R.color.chrome_outline)
        )
    }

    // ==================================================================
    // Navigation
    // ==================================================================

    private fun submitInput(rawText: String?, actionId: Int, event: KeyEvent?): Boolean {
        val shouldGo = actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_SEARCH ||
            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        if (!shouldGo) return false
        val rawInput = rawText?.trim().orEmpty()
        if (rawInput.isNotEmpty()) {
            hideSuggestions()
            if (preferences.saveSearchHistory && !looksLikeUrl(rawInput)) {
                databaseHelper.addToHistory(
                    HistoryItem(title = rawInput, url = normalizeUrl(rawInput))
                )
            }
            loadUrl(normalizeUrl(rawInput))
        }
        return true
    }

    private fun looksLikeUrl(input: String): Boolean {
        return input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true) ||
            (input.contains(".") && !input.contains(" "))
    }

    private fun normalizeUrl(rawInput: String): String {
        return when {
            rawInput.startsWith("http://", ignoreCase = true) ||
                rawInput.startsWith("https://", ignoreCase = true) -> rawInput

            rawInput.contains(".") && !rawInput.contains(" ") -> "https://$rawInput"
            else -> preferences.searchEngine.searchUrl(rawInput)
        }
    }

    private fun loadUrl(url: String) {
        val target = if (url == homeUrl) homeUrl else normalizeUrl(url)
        if (target == homeUrl) {
            loadHomeInCurrentTab()
            return
        }
        val session = tabManager.current ?: return
        session.url = target
        if (session.title.isBlank() || session.title.startsWith("http", ignoreCase = true)) {
            session.title = target.toUri().host?.removePrefix("www.") ?: target
        }
        applyPrivacyMode()
        binding.homeSearchInput.setText("")
        binding.urlInput.clearFocus()
        hideKeyboard()
        hideSuggestions()
        session.webView.loadUrl(target)
        hideTabSwitcher()
    }

    /** Load whatever URL a tab already holds — used when switching or restoring tabs. */
    private fun loadSession(session: TabSession) {
        currentPageTitle = session.title
        applyPrivacyMode()
        if (session.isNewTab) {
            showHome()
        } else {
            session.webView.loadUrl(session.url)
            showWeb()
        }
    }

    private fun loadHomeInCurrentTab() {
        val session = tabManager.current ?: return
        session.url = homeUrl
        session.title = getString(
            if (session.incognito) R.string.new_incognito_tab else R.string.new_tab
        )
        currentPageTitle = ""
        applyPrivacyMode()
        binding.homeSearchInput.setText("")
        binding.urlInput.setText("")
        binding.urlInput.clearFocus()
        showHome()
        refreshTabUi()
    }

    private fun showHome() {
        applyCurrentTabTheme()
        refreshHomeContent()
        binding.homeScroll.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.GONE
        binding.progressBar.hide()
        binding.incognitoLabel.visibility = if (isCurrentTabIncognito()) View.VISIBLE else View.GONE
        binding.feedHeader.visibility = View.VISIBLE
        binding.omniboxContainer.visibility = View.VISIBLE
        updateNavigationState()
    }

    private fun showWeb() {
        applyCurrentTabTheme()
        binding.homeScroll.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.omniboxContainer.visibility = View.VISIBLE
        updateNavigationState()
    }

    private fun updateNavigationState() {
        val onWeb = isShowingWeb()
        binding.backButton.visibility =
            if (onWeb && activeWebView?.canGoBack() == true) View.VISIBLE else View.GONE
        updateSecurityIcon()
    }

    private fun updateSecurityIcon() {
        val session = tabManager.current
        val iconRes = when {
            session?.incognito == true -> R.drawable.ic_incognito_24
            !isShowingWeb() -> R.drawable.ic_search_24
            session?.url?.startsWith("https://") == true -> R.drawable.ic_lock_24
            session?.url?.startsWith("http://") == true -> R.drawable.ic_info_24
            else -> R.drawable.ic_search_24
        }
        binding.securityIcon.setImageResource(iconRes)
    }

    private fun showTabSwitcher() {
        refreshTabUi()
        binding.tabSwitcherPanel.visibility = View.VISIBLE
        binding.tabSearchInput.setText("")
        hideKeyboard()
    }

    private fun hideTabSwitcher() {
        binding.tabSwitcherPanel.visibility = View.GONE
    }

    private fun addNewTab(incognito: Boolean) {
        if (incognito && !preferences.incognitoEnabled) return
        tabManager.newTab(
            incognito = incognito,
            url = homeUrl,
            title = getString(if (incognito) R.string.new_incognito_tab else R.string.new_tab),
            activate = true
        )
        loadHomeInCurrentTab()
        hideTabSwitcher()
    }

    /** Open a URL in a brand new tab and switch to it. */
    private fun openInNewTab(url: String, incognito: Boolean) {
        if (incognito && !preferences.incognitoEnabled) return
        val target = normalizeUrl(url)
        val session = tabManager.newTab(
            incognito = incognito,
            url = target,
            title = target.toUri().host?.removePrefix("www.") ?: target,
            activate = true
        )
        hideTabSwitcher()
        loadSession(session)
    }

    /** Close the tab at [index]; when it was the last one, start a fresh New Tab Page. */
    private fun closeTab(index: Int) {
        val closing = tabManager.tabAt(index) ?: return
        val wasIncognito = closing.incognito
        val survivor = tabManager.closeTab(index)

        // Drop this tab's detections, otherwise they linger for the process lifetime.
        MediaRegistry.clear(closing.id)

        if (wasIncognito) clearPrivateWebData()

        if (survivor == null) {
            // KoiBrowser kept the window alive rather than finishing the activity.
            tabManager.newTab(incognito = false, url = homeUrl, activate = true)
            showHome()
        } else if (survivor === tabManager.current) {
            currentPageTitle = survivor.title
            applyPrivacyMode()
            if (survivor.isNewTab) showHome() else showWeb()
        }
        refreshTabUi()
        if (binding.tabSwitcherPanel.visibility == View.VISIBLE) showTabSwitcher()
    }

    /** Called after the tab switcher selects a tab. */
    private fun onTabSelected() {
        val session = tabManager.current ?: return
        currentPageTitle = session.title
        applyPrivacyMode()
        refreshTabUi()
        if (session.isNewTab) showHome() else showWeb()
        hideTabSwitcher()
    }

    private fun refreshTabUi() {
        if (!::tabsAdapter.isInitialized) return
        updateTabGridSpanCount()
        val tabCount = tabManager.count.coerceAtLeast(1).toString()
        val borderColorRes = if (isCurrentTabIncognito()) {
            R.color.incognito_text
        } else {
            R.color.chrome_text
        }
        binding.tabCountButton.text = tabCount
        binding.tabCountButton.background = buildTabCountBackground(borderColorRes)
        binding.tabCountButton.setTextColor(resolveColor(borderColorRes))
        binding.tabSwitcherCount.text = tabCount
        binding.tabSwitcherCount.background = buildTabCountBackground(borderColorRes)
        binding.tabSwitcherCount.setTextColor(resolveColor(borderColorRes))
        tabsAdapter.submitTabs(
            tabManager.all,
            tabManager.currentIndex,
            binding.tabSearchInput.text?.toString().orEmpty()
        )
    }

    // ==================================================================
    // Omnibox autocomplete — ported from KoiBrowser's SuggestionsManager
    // ==================================================================

    private fun requestSuggestions(query: String) {
        val trimmed = query.trim()
        if (!preferences.searchSuggestions || trimmed.isEmpty() || looksLikeUrl(trimmed)) {
            hideSuggestions()
            return
        }
        suggestionsExecutor.execute {
            val suggestions = SuggestionsProvider.fetch(this, trimmed)
            runOnUiThread {
                // Discard stale results if the user kept typing.
                if (binding.urlInput.text?.toString()?.trim() != trimmed) return@runOnUiThread
                if (suggestions.isEmpty() || !omniboxFocused) {
                    hideSuggestions()
                } else {
                    suggestionAdapter.submit(suggestions, trimmed)
                    binding.suggestionPanel.visibility = View.VISIBLE
                    omniboxSuggestionsVisible = true
                }
            }
        }
    }

    private fun hideSuggestions() {
        if (!::suggestionAdapter.isInitialized) return
        binding.suggestionPanel.visibility = View.GONE
        omniboxSuggestionsVisible = false
        suggestionAdapter.clear()
    }

    // ==================================================================
    // Find in page — ported from KoiBrowser's openFindInPage / findUp / findDown
    // ==================================================================

    private fun openFindInPage() {
        if (!isShowingWeb()) return
        binding.findBar.visibility = View.VISIBLE
        binding.findInput.requestFocus()
        binding.findInput.selectAll()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
        val webView = activeWebView ?: return
        webView.findAllAsync(findQuery.ifEmpty { binding.findInput.text?.toString().orEmpty() })
    }

    private fun closeFindInPage() {
        activeWebView?.clearMatches()
        binding.findBar.visibility = View.GONE
        binding.findCount.text = ""
        findQuery = ""
        findMatches = 0
        hideKeyboard()
    }

    /** `WebView.findAllAsync` reports back through this callback (set once, per WebView). */
    private fun attachFindListener(webView: WebView) {
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (!isDoneCounting) return@setFindListener
            findMatches = numberOfMatches
            binding.findCount.text = if (numberOfMatches == 0) {
                getString(R.string.find_no_matches)
            } else {
                getString(R.string.find_match_count, activeMatchOrdinal, numberOfMatches)
            }
        }
    }

    // ==================================================================
    // Chrome bottom-sheet menu
    // ==================================================================

    /**
     * Chrome's overflow menu is a bottom sheet. Every entry dismisses the sheet before running
     * its action, so tapping an option always closes the menu.
     */
    private fun showBrowserMenu() {
        if (browserMenu?.isShowing == true) return

        val menuBinding = DialogBrowserMenuBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this, R.style.Theme_Extremis_BottomSheet)
        dialog.setContentView(menuBinding.root)

        populateMenuHeader(menuBinding, dialog)
        wireQuickActions(menuBinding, dialog)
        populateMenuItems(menuBinding, dialog)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { browserMenu = null }
        browserMenu = dialog
        dialog.show()
    }

    private fun populateMenuHeader(
        menuBinding: DialogBrowserMenuBinding,
        dialog: BottomSheetDialog
    ) {
        val url = tabManager.current?.url.orEmpty()
        val onPage = url != homeUrl && url.isNotBlank()
        if (!onPage) {
            menuBinding.menuHeader.visibility = View.GONE
            return
        }

        menuBinding.menuHeader.visibility = View.VISIBLE
        menuBinding.menuHeader.removeAllViews()
        menuBinding.menuHeader.addView(
            TextView(this).apply {
                text = currentPageTitle.ifBlank { FaviconLoader.hostOf(url) ?: url }
                setTextColor(resolveColor(R.color.chrome_text))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        menuBinding.menuHeader.addView(
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                text = url
                setTextColor(resolveColor(R.color.chrome_muted))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                isClickable = true
                background = rippleBackground()
                setOnClickListener {
                    copyToClipboard(url)
                    dialog.dismiss()
                }
            }
        )
    }

    private fun wireQuickActions(
        menuBinding: DialogBrowserMenuBinding,
        dialog: BottomSheetDialog
    ) {
        val onWeb = isShowingWeb()

        menuBinding.actionForward.apply {
            isEnabled = onWeb && activeWebView?.canGoForward() == true
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener {
                dialog.dismiss()
                val webView = activeWebView
                if (webView != null && webView.canGoForward()) {
                    webView.goForward()
                    updateNavigationState()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.forward,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        menuBinding.actionBookmark.apply {
            val bookmarked = isCurrentPageBookmarked()
            setImageResource(
                if (bookmarked) R.drawable.ic_star_filled_24 else R.drawable.ic_star_outline_24
            )
            setOnClickListener {
                dialog.dismiss()
                toggleBookmark()
            }
        }

        menuBinding.actionShare.setOnClickListener {
            dialog.dismiss()
            shareCurrentPage()
        }

        menuBinding.actionDownload.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        menuBinding.actionReload.setOnClickListener {
            dialog.dismiss()
            if (onWeb) activeWebView?.reload() else showHome()
        }
    }

    private fun populateMenuItems(
        menuBinding: DialogBrowserMenuBinding,
        dialog: BottomSheetDialog
    ) {
        val items = menuBinding.menuItems
        items.removeAllViews()

        items.addMenuRow(R.drawable.ic_add_24, getString(R.string.new_tab), dialog) {
            addNewTab(false)
        }
        items.addMenuRow(
            R.drawable.ic_incognito_24,
            getString(R.string.new_incognito_tab),
            dialog
        ) { addNewTab(true) }
        items.addMenuDivider()
        items.addMenuRow(R.drawable.ic_history_24, getString(R.string.history), dialog) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        items.addMenuRow(
            R.drawable.ic_delete_sweep_24,
            getString(R.string.delete_browsing_data),
            dialog
        ) { confirmClearBrowsingData() }
        items.addMenuDivider()
        items.addMenuRow(R.drawable.ic_download_24, getString(R.string.downloads), dialog) {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        items.addMenuRow(
            R.drawable.ic_video_download_24,
            getString(R.string.download_video),
            dialog
        ) { showMediaSheet() }
        items.addMenuRow(R.drawable.ic_bookmark_24, getString(R.string.bookmarks), dialog) {
            startActivity(Intent(this, BookmarksActivity::class.java))
        }
        items.addMenuRow(R.drawable.ic_recent_tabs_24, getString(R.string.recent_tabs), dialog) {
            showTabSwitcher()
        }
        items.addMenuDivider()
        // Page tools, ported from KoiBrowser's pop_window_tools menus.
        items.addMenuRow(R.drawable.ic_search_24, getString(R.string.find_in_page), dialog) {
            openFindInPage()
        }
        items.addMenuRow(R.drawable.ic_camera_24, getString(R.string.screenshot), dialog) {
            captureScreenshot()
        }
        items.addMenuRow(R.drawable.ic_globe_24, getString(R.string.translate_page), dialog) {
            translateCurrentPage()
        }
        items.addMenuRow(R.drawable.ic_add_24, getString(R.string.add_to_home_screen), dialog) {
            addToHomeScreen()
        }
        items.addMenuDivider()
        items.addMenuRow(R.drawable.ic_folder_24, getString(R.string.open_file_browser), dialog) {
            startActivity(Intent(this, FileBrowserActivity::class.java))
        }
        items.addMenuRow(
            R.drawable.ic_new_window_24,
            getString(R.string.desktop_site),
            dialog
        ) { toggleDesktopMode() }
        if (preferences.adFree) {
            items.addMenuRow(
                R.drawable.ic_star_filled_24,
                getString(R.string.ad_free_active),
                dialog
            ) { showAdFreeStatusDialog() }
        } else {
            val price = BillingManager.priceLabel ?: getString(R.string.ad_free_price_fallback)
            items.addMenuRow(
                R.drawable.ic_star_24,
                getString(R.string.remove_ads, price),
                dialog
            ) { showAdFreeOfferDialog() }
        }

        items.addMenuRow(R.drawable.ic_settings_24, getString(R.string.settings), dialog) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        items.addMenuRow(R.drawable.ic_delete_24, getString(R.string.clear_everything), dialog) {
            confirmPanicWipe()
        }
        items.addMenuRow(R.drawable.ic_help_24, getString(R.string.help_feedback), dialog) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://support.google.com/chrome".toUri()
                )
            )
        }
    }

    private fun LinearLayout.addMenuRow(
        iconRes: Int,
        label: String,
        dialog: BottomSheetDialog,
        action: () -> Unit
    ) {
        addView(
            TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.chrome_sheet_row_height)
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                text = label
                textSize = 15f
                setTextColor(resolveColor(R.color.chrome_text))
                val icon = ContextCompat.getDrawable(this@MainActivity, iconRes)
                icon?.setTint(resolveColor(R.color.chrome_icon))
                // Chrome places menu icons on the trailing edge.
                setCompoundDrawablesWithIntrinsicBounds(null, null, icon, null)
                compoundDrawablePadding = dp(20)
                background = rippleBackground()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
        )
    }

    private fun LinearLayout.addMenuDivider() {
        addView(
            View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                    marginStart = dp(20)
                    marginEnd = dp(20)
                }
                setBackgroundColor(resolveColor(R.color.chrome_outline))
            }
        )
    }

    // ==================================================================
    // Page actions
    // ==================================================================

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_or_type_url))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startImageSearch() {
        loadUrl("https://lens.google.com/")
    }

    private fun currentUrl(): String = tabManager.current?.url.orEmpty()

    private fun isCurrentPageBookmarked(): Boolean {
        val url = currentUrl()
        return url != homeUrl && url.isNotBlank() && databaseHelper.isBookmarked(url)
    }

    private fun toggleBookmark() {
        val url = currentUrl()
        if (url == homeUrl || url.isBlank()) {
            Toast.makeText(this, R.string.open_a_page_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (databaseHelper.isBookmarked(url)) {
            databaseHelper.deleteBookmarkByUrl(url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            databaseHelper.addBookmark(
                Bookmark(
                    title = currentPageTitle.ifBlank { tabManager.current?.title.orEmpty() },
                    url = url
                )
            )
            Toast.makeText(this, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAddBookmarkShortcut() {
        val url = currentUrl()
        if (url == homeUrl || url.isBlank()) {
            startActivity(Intent(this, BookmarksActivity::class.java))
            return
        }
        toggleBookmark()
    }

    private fun shareCurrentPage() {
        val url = currentUrl()
        if (url == homeUrl || url.isBlank()) {
            Toast.makeText(this, R.string.open_a_page_first, Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentPageTitle.ifBlank { url })
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }

    private fun showPageInfo() {
        val url = currentUrl()
        if (url == homeUrl || url.isBlank()) return
        val secure = url.startsWith("https://")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.page_info_title)
            .setMessage(
                getString(
                    if (secure) R.string.connection_secure else R.string.connection_not_secure
                ) + "\n\n" + url
            )
            .setPositiveButton(R.string.copy_link) { _, _ -> copyToClipboard(url) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String, confirmationRes: Int) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, confirmationRes, Toast.LENGTH_SHORT).show()
    }

    private fun toggleDesktopMode() {
        preferences.desktopMode = !preferences.desktopMode
        val desktop = preferences.desktopMode
        tabManager.all.forEach { session ->
            session.webView.settings.userAgentString =
                if (desktop) DESKTOP_USER_AGENT else null
        }
        if (isShowingWeb()) activeWebView?.reload()
        Toast.makeText(
            this,
            if (desktop) R.string.desktop_site_on else R.string.desktop_site_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmClearBrowsingData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_browsing_data)
            .setMessage(R.string.delete_browsing_data_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> clearBrowsingData() }
            .show()
    }

    private fun clearBrowsingData() {
        databaseHelper.clearHistory()
        tabManager.all.forEach { session ->
            session.webView.clearCache(true)
            session.webView.clearHistory()
            session.webView.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        FaviconLoader.clear()
        Toast.makeText(this, R.string.browsing_data_deleted, Toast.LENGTH_SHORT).show()
        refreshHomeContent()
    }

    // ==================================================================
    // KoiBrowser feature ports: screenshot, translate, shortcut, wipe
    // ==================================================================

    /**
     * Capture the visible page. Ported from KoiBrowser's `ScreenshotTask` +
     * `MainActivity.captureScreen`.
     *
     * KoiBrowser wrote straight to `Environment.getExternalStoragePublicDirectory`, which is
     * blocked from Android 10 onwards. We route through MediaStore on API 29+ and only fall back
     * to a direct file write on older releases.
     */
    private fun captureScreenshot() {
        val webView = activeWebView
        if (webView == null || !isShowingWeb()) {
            Toast.makeText(this, R.string.open_a_page_first, Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = try {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { out ->
                val canvas = Canvas(out)
                webView.draw(canvas)
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "extremis_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveScreenshotViaMediaStore(bitmap, fileName)
            } else {
                saveScreenshotToPictures(bitmap, fileName)
            }
            Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveScreenshotViaMediaStore(bitmap: Bitmap, fileName: String) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ExtremisBrowser"
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    @Suppress("DEPRECATION")
    private fun saveScreenshotToPictures(bitmap: Bitmap, fileName: String) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "ExtremisBrowser"
        )
        if (!directory.exists()) directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    /** Inject Google Translate into the current page — KoiBrowser's `translatePage`. */
    private fun translateCurrentPage() {
        val webView = activeWebView
        val url = currentUrl()
        if (webView == null || url == homeUrl || url.isBlank()) {
            Toast.makeText(this, R.string.open_a_page_first, Toast.LENGTH_SHORT).show()
            return
        }
        val translateUrl =
            "https://translate.google.com/translate?sl=auto&tl=${java.util.Locale.getDefault().language}&u=" +
                Uri.encode(url)
        webView.loadUrl(translateUrl)
    }

    /**
     * Pin the current page to the launcher — KoiBrowser's `makeShortcut`.
     * Uses the platform ShortcutManager so no `INSTALL_SHORTCUT` permission is required.
     */
    private fun addToHomeScreen() {
        val url = currentUrl()
        if (url == homeUrl || url.isBlank()) {
            Toast.makeText(this, R.string.open_a_page_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.shortcut_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val manager = getSystemService(ShortcutManager::class.java)
            val label = currentPageTitle.ifBlank {
                FaviconLoader.hostOf(url) ?: getString(R.string.app_name)
            }
            val shortcut = ShortcutInfo.Builder(this, "page_${System.currentTimeMillis()}")
                .setShortLabel(label.take(20))
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(createLaunchIntent(this, url))
                .build()
            val requested = manager?.requestPinShortcut(shortcut, null) ?: false
            Toast.makeText(
                this,
                if (requested) R.string.shortcut_added else R.string.shortcut_failed,
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.shortcut_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * KoiBrowser's "panic" wipe (it bound this to the Guardian Project panic trigger): erase
     * browsing data, cookies, cache, bookmarks, history and every open tab.
     */
    private fun confirmPanicWipe() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_everything)
            .setMessage(R.string.clear_everything_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> panicWipe() }
            .show()
    }

    private fun panicWipe() {
        databaseHelper.clearHistory()
        databaseHelper.clearBookmarks()
        tabManager.all.forEach { session ->
            session.webView.clearCache(true)
            session.webView.clearHistory()
            session.webView.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        FaviconLoader.clear()
        preferences.savedTabs = null
        preferences.resetStats()
        AdBlocker.setBlockedCount(0L)

        tabManager.shutdown()
        tabManager.newTab(incognito = false, url = homeUrl, activate = true)
        showHome()
        refreshTabUi()
        Toast.makeText(this, R.string.everything_cleared, Toast.LENGTH_SHORT).show()
    }

    // ==================================================================
    // Long-press context menus — KoiBrowser's pop_window_tools_*.xml
    // ==================================================================

    /**
     * Resolve what the user is pressing on and offer the matching actions.
     *
     * KoiBrowser inflated one of two popup layouts depending on whether a link or an image was
     * hit. We use a Material dialog with the same action set, which keeps the Chrome look.
     */
    private fun handleLongPress(webView: WebView): Boolean {
        val result = webView.hitTestResult ?: return false
        val extra = result.extra
        return when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                if (extra.isNullOrBlank()) false else {
                    showLinkMenu(extra); true
                }
            }

            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // The image URL needs a second hit test to resolve.
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val message = handler.obtainMessage()
                webView.requestFocusNodeHref(message)
                val imageUrl = message.data?.getString("src")
                if (imageUrl.isNullOrBlank()) {
                    if (extra.isNullOrBlank()) false else {
                        showLinkMenu(extra); true
                    }
                } else {
                    showImageMenu(imageUrl, extra); true
                }
            }

            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra.isNullOrBlank()) false else {
                    showImageMenu(extra, null); true
                }
            }

            WebView.HitTestResult.EMAIL_TYPE -> {
                if (extra.isNullOrBlank()) false else {
                    startActivity(Intent(Intent.ACTION_SENDTO, "mailto:${extra}".toUri())); true
                }
            }

            WebView.HitTestResult.PHONE_TYPE -> {
                if (extra.isNullOrBlank()) false else {
                    startActivity(Intent(Intent.ACTION_DIAL, "tel:${extra}".toUri())); true
                }
            }

            else -> false
        }
    }

    private fun showLinkMenu(url: String) {
        val actions = arrayOf(
            getString(R.string.open_in_new_tab),
            getString(R.string.open_in_incognito),
            getString(R.string.copy_link),
            getString(R.string.download_link),
            getString(R.string.share)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_actions)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        openInNewTab(url, incognito = false)
                        Toast.makeText(this, R.string.new_tab_opened, Toast.LENGTH_SHORT).show()
                    }

                    1 -> {
                        openInNewTab(url, incognito = true)
                        Toast.makeText(this, R.string.new_tab_opened, Toast.LENGTH_SHORT).show()
                    }

                    2 -> copyText(url, R.string.link_copied)
                    3 -> handleDownload(url, null, null, null)
                    4 -> {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                getString(R.string.share)
                            )
                        )
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showImageMenu(imageUrl: String, linkUrl: String?) {
        val actions = arrayOf(
            getString(R.string.save_image),
            getString(R.string.copy_image_url),
            getString(R.string.open_in_new_tab)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.image_actions)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> handleDownload(imageUrl, null, null, "image/*")
                    1 -> copyText(imageUrl, R.string.image_url_copied)
                    2 -> openInNewTab(linkUrl ?: imageUrl, incognito = false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** KoiBrowser incremented a launch counter and prompted for a rating after a few runs. */
    private fun maybePromptForRating() {
        val launches = preferences.launchCount
        if (preferences.ratePromptHandled || launches < RATE_PROMPT_AFTER_LAUNCHES) return
        if (launches % RATE_PROMPT_INTERVAL != 0) return
        preferences.ratePromptHandled = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rate_app)
            .setMessage(R.string.rate_app_message)
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.rate_now) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "market://details?id=$packageName".toUri()
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://play.google.com/store/apps/details?id=$packageName".toUri()
                        )
                    )
                }
            }
            .show()
    }

    // ==================================================================
    // Theme (normal + incognito)
    // ==================================================================

    private fun applyCurrentTabTheme() {
        if (isCurrentTabIncognito()) applyIncognitoTheme() else applyNormalTheme()
    }

    private fun applyNormalTheme() {
        val page = resolveColor(R.color.chrome_page_bg)
        val text = resolveColor(R.color.chrome_text)
        val muted = resolveColor(R.color.chrome_muted)
        val icon = resolveColor(R.color.chrome_icon)

        paintSurfaces(page, lightBars = true)

        binding.urlInput.setTextColor(text)
        binding.urlInput.setHintTextColor(muted)
        binding.homeSearchInput.setTextColor(text)
        binding.homeSearchInput.setHintTextColor(muted)
        binding.tabSearchInput.setTextColor(text)
        binding.tabSearchInput.setHintTextColor(muted)

        binding.securityIcon.setColorFilter(icon)
        binding.backButton.setColorFilter(icon)
        binding.menuButton.setColorFilter(icon)
        binding.tabSwitcherMenuButton.setColorFilter(icon)
        binding.newTabButton.setColorFilter(icon)
        binding.omniVoiceButton.setColorFilter(icon)
        binding.omniLensButton.setColorFilter(icon)
        binding.voiceButton.setColorFilter(icon)
        binding.lensButton.setColorFilter(icon)

        binding.seeMoreButton.setTextColor(resolveColor(R.color.chrome_blue))
        binding.incognitoLabel.setTextColor(muted)
        applyWordmarkColors(incognito = false)
        applySearchSurfaces(incognito = false)
    }

    private fun applyIncognitoTheme() {
        val page = resolveColor(R.color.incognito_page_bg)
        val text = resolveColor(R.color.incognito_text)
        val muted = resolveColor(R.color.incognito_muted)

        paintSurfaces(page, lightBars = false)

        binding.urlInput.setTextColor(text)
        binding.urlInput.setHintTextColor(muted)
        binding.homeSearchInput.setTextColor(text)
        binding.homeSearchInput.setHintTextColor(muted)
        binding.tabSearchInput.setTextColor(text)
        binding.tabSearchInput.setHintTextColor(muted)

        binding.securityIcon.setColorFilter(muted)
        binding.backButton.setColorFilter(muted)
        binding.menuButton.setColorFilter(muted)
        binding.tabSwitcherMenuButton.setColorFilter(muted)
        binding.newTabButton.setColorFilter(muted)
        binding.omniVoiceButton.setColorFilter(muted)
        binding.omniLensButton.setColorFilter(muted)
        binding.voiceButton.setColorFilter(muted)
        binding.lensButton.setColorFilter(muted)

        binding.seeMoreButton.setTextColor(resolveColor(R.color.incognito_text))
        binding.incognitoLabel.setTextColor(text)
        applyWordmarkColors(incognito = true)
        applySearchSurfaces(incognito = true)
    }

    private fun paintSurfaces(color: Int, lightBars: Boolean) {
        binding.root.setBackgroundColor(color)
        binding.browserContainer.setBackgroundColor(color)
        binding.topControls.setBackgroundColor(color)
        binding.contentFrame.setBackgroundColor(color)
        binding.homeScroll.setBackgroundColor(color)
        binding.homeContent.setBackgroundColor(color)
        binding.tabSwitcherPanel.setBackgroundColor(color)
        binding.adViewContainer.setBackgroundColor(color)
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    private fun applySearchSurfaces(incognito: Boolean) {
        binding.homeSearchBar.setBackgroundResource(
            if (incognito) R.drawable.incognito_search_bg else R.drawable.chrome_search_bg
        )
        binding.tabSearchInput.setBackgroundResource(
            if (incognito) R.drawable.incognito_search_bg else R.drawable.chrome_search_bg
        )
        updateOmniboxBackground()
    }

    private fun updateOmniboxBackground() {
        val incognito = isCurrentTabIncognito()
        binding.omniboxContainer.setBackgroundResource(
            when {
                incognito && omniboxFocused -> R.drawable.incognito_omnibox_bg_focused
                incognito -> R.drawable.incognito_omnibox_bg
                omniboxFocused -> R.drawable.chrome_omnibox_bg_focused
                else -> R.drawable.chrome_omnibox_bg
            }
        )
    }

    /** The New Tab Page wordmark: one Google brand colour per letter. */
    private fun applyWordmarkColors(incognito: Boolean) {
        val letters = listOf(
            binding.logoLetter1, binding.logoLetter2, binding.logoLetter3,
            binding.logoLetter4, binding.logoLetter5, binding.logoLetter6
        )
        val brandColors = listOf(
            R.color.google_blue, R.color.google_red, R.color.google_yellow,
            R.color.google_blue, R.color.google_green, R.color.google_red
        )
        letters.forEachIndexed { index, letter ->
            letter.setTextColor(
                if (incognito) {
                    resolveColor(R.color.incognito_muted)
                } else {
                    resolveColor(brandColors[index])
                }
            )
        }
    }

    // ==================================================================
    // Privacy
    // ==================================================================

    private fun applyPrivacyMode() {
        val incognito = isCurrentTabIncognito()
        val modeChanged = lastAppliedIncognito != incognito

        IncognitoModeState.isIncognitoActive = incognito

        // cacheMode and third-party cookies are per-tab; re-assert them on the active WebView.
        activeWebView?.let { webView ->
            webView.settings.cacheMode = if (incognito) {
                WebSettings.LOAD_NO_CACHE
            } else {
                WebSettings.LOAD_DEFAULT
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !incognito)
        }

        if (modeChanged) {
            activeWebView?.let { webView ->
                webView.clearHistory()
                webView.clearFormData()
                webView.clearSslPreferences()
            }
            if (!incognito) {
                clearPrivateWebData()
            }
            refreshAdVisibility(forceReload = !incognito)
            lastAppliedIncognito = incognito
        }

        if (incognito) {
            activeWebView?.clearCache(false)
        }
    }

    private fun clearPrivateWebData() {
        activeWebView?.let { webView ->
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()
            webView.clearCache(true)
        }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeSessionCookies(null)
        CookieManager.getInstance().flush()
    }

    // ==================================================================
    // Ads
    // ==================================================================

    private fun setupAds() {
        // A completed purchase has to take effect immediately, not on the next launch.
        BillingManager.onEntitlementChanged = {
            runOnUiThread { refreshAdVisibility(forceReload = false) }
        }
        refreshAdVisibility()
        runCatching {
            MobileAds.initialize(this) {
                refreshAdVisibility(forceReload = true)
            }
        }.onFailure {
            binding.adViewContainer.visibility = View.GONE
            binding.nativeAdContainer.visibility = View.GONE
        }
    }

    /**
     * The subscription offer.
     *
     * The price comes from Play so it always matches what the user is actually charged; the
     * fallback label only appears before Play has answered.
     */
    private fun showAdFreeOfferDialog() {
        val price = BillingManager.priceLabel ?: getString(R.string.ad_free_price_fallback)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ad_free_title)
            .setMessage(getString(R.string.ad_free_message, price))
            .setPositiveButton(R.string.ad_free_subscribe) { _, _ ->
                if (!BillingManager.subscribe(this)) {
                    Toast.makeText(this, R.string.ad_free_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.ad_free_restore) { _, _ ->
                BillingManager.refresh()
                Toast.makeText(this, R.string.ad_free_checking, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shown instead of the offer once the subscription is active. */
    private fun showAdFreeStatusDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ad_free_active)
            .setMessage(R.string.ad_free_active_message)
            .setPositiveButton(R.string.ad_free_manage) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, BillingManager.manageUri()))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadBannerAd() {
        // refreshAdVisibility is the only caller today, but the gate belongs here too so a new
        // caller cannot leak a banner to a subscriber.
        if (IncognitoModeState.isIncognitoActive || preferences.adFree) {
            binding.adViewContainer.visibility = View.GONE
            return
        }

        binding.adViewContainer.post {
            val adWidth = getBannerAdWidth()
            if (adWidth <= 0) {
                binding.adViewContainer.visibility = View.GONE
                return@post
            }

            val adView = AdView(this).apply {
                adUnitId = getString(R.string.banner_ad_unit_id)
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        this@MainActivity,
                        adWidth
                    )
                )
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        binding.adViewContainer.visibility = View.VISIBLE
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (bannerAdView === this@apply) {
                            bannerAdView = null
                        }
                        binding.adViewContainer.removeAllViews()
                        binding.adViewContainer.visibility = View.GONE
                    }
                }
            }

            bannerAdView?.destroy()
            bannerAdView = adView
            binding.adViewContainer.removeAllViews()
            binding.adViewContainer.addView(adView)
            binding.adViewContainer.visibility = View.GONE
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
        if (!AdConfig.FULL_SCREEN_ADS_ENABLED) return
        if (preferences.adFree) return
        if (IncognitoModeState.isIncognitoActive) return
        if (interstitialLoading || interstitialAd != null) return
        interstitialLoading = true
        InterstitialAd.load(
            this,
            getString(R.string.interstitial_ad_unit_id),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialAd = ad
                    interstitialAd?.fullScreenContentCallback = object :
                        FullScreenContentCallback() {
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

    /**
     * Counts a finished page and shows an interstitial when the cadence allows one.
     *
     * Skipped on the first pages of a session, while a video is on screen or full screen, in
     * incognito, on the New Tab Page and for subscribers. An ad that cuts into a video is the
     * placement users complain about most.
     */
    private fun maybeShowInterstitial(url: String) {
        if (!AdConfig.FULL_SCREEN_ADS_ENABLED) return
        if (preferences.adFree || IncognitoModeState.isIncognitoActive) return
        if (!url.startsWith("http")) return
        if (!isShowingWeb()) return
        if (binding.fullscreenContainer.visibility == View.VISIBLE) return
        if (MediaCapture.hasVideo()) return

        pagesFinished++
        if (pagesFinished < AdConfig.INTERSTITIAL_WARMUP_PAGES) return
        if (pagesFinished % AdConfig.INTERSTITIAL_PAGE_INTERVAL != 0) return

        showInterstitialIfReady()
    }

    private fun showInterstitialIfReady() {
        if (!AdConfig.FULL_SCREEN_ADS_ENABLED) return
        if (preferences.adFree) return
        if (IncognitoModeState.isIncognitoActive) return
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShowTime < AdConfig.INTERSTITIAL_COOLDOWN_MS) {
            return
        }
        val ad = interstitialAd
        if (ad == null) {
            loadInterstitialAd()
            return
        }
        lastInterstitialShowTime = now
        ad.show(this)
    }

    private fun loadNativeAd() {
        if (IncognitoModeState.isIncognitoActive) {
            binding.nativeAdContainer.visibility = View.GONE
            return
        }

        binding.nativeAdContainer.visibility = View.GONE

        val adLoader = AdLoader.Builder(this, getString(R.string.native_ad_unit_id))
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
        adView.priceView = nativeAdBinding.adPrice
        adView.storeView = nativeAdBinding.adStore

        nativeAdBinding.adHeadline.text = ad.headline

        val mediaContent = ad.mediaContent
        val hasMedia = mediaContent != null && (mediaContent.hasVideoContent() || mediaContent.mainImage != null || ad.images.isNotEmpty())
        if (hasMedia) {
            nativeAdBinding.adMedia.mediaContent = mediaContent
            nativeAdBinding.adMedia.setImageScaleType(ImageView.ScaleType.CENTER_CROP)
            nativeAdBinding.adMedia.visibility = View.VISIBLE
        } else {
            nativeAdBinding.adMedia.visibility = View.GONE
        }

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

        val price = ad.price
        nativeAdBinding.adPrice.visibility = if (price.isNullOrBlank()) View.GONE else View.VISIBLE
        nativeAdBinding.adPrice.text = price.orEmpty()

        val store = ad.store
        nativeAdBinding.adStore.visibility = if (store.isNullOrBlank()) View.GONE else View.VISIBLE
        nativeAdBinding.adStore.text = store.orEmpty()

        adView.setNativeAd(ad)
    }

    private fun refreshAdVisibility(forceReload: Boolean = false) {
        // Incognito and a paid subscription both mean no ads anywhere.
        val shouldShowAds = !IncognitoModeState.isIncognitoActive && !preferences.adFree
        if (!shouldShowAds) {
            binding.adViewContainer.removeAllViews()
            binding.adViewContainer.visibility = View.GONE
            bannerAdView?.destroy()
            bannerAdView = null

            binding.nativeAdContainer.removeAllViews()
            binding.nativeAdContainer.visibility = View.GONE
            nativeAd?.destroy()
            nativeAd = null

            interstitialAd = null
            interstitialLoading = false
            return
        }

        if (forceReload || bannerAdView == null) {
            loadBannerAd()
        } else {
            binding.adViewContainer.visibility = View.VISIBLE
        }

        if (forceReload || nativeAd == null || binding.nativeAdContainer.childCount == 0) {
            loadNativeAd()
        } else {
            binding.nativeAdContainer.visibility = View.VISIBLE
        }

        loadInterstitialAd()
    }

    // ==================================================================
    // Misc helpers
    // ==================================================================

    private fun updateTabGridSpanCount() {
        if (!::tabsLayoutManager.isInitialized) return
        val recyclerWidth = binding.tabsRecycler.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val spanCount = (recyclerWidth / dp(180)).coerceIn(1, 4)
        if (tabsLayoutManager.spanCount != spanCount) {
            tabsLayoutManager.spanCount = spanCount
        }
    }

    private fun buildTabCountBackground(strokeColorRes: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(2), resolveColor(strokeColorRes))
        }
    }

    private fun rippleBackground(): Drawable {
        val color = ColorStateList.valueOf(resolveColor(R.color.chrome_ripple))
        return RippleDrawable(color, null, null)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
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
                setDescription(getString(R.string.downloads))
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()

                // KoiBrowser's KEY_DOWNLOAD_VIA_WIFI_ONLY.
                if (preferences.downloadWifiOnly) {
                    setAllowedOverMetered(false)
                    setAllowedOverRoaming(false)
                }

                // KoiBrowser's COMPLETE_NOTIFICATION_SOUND_KEY.
                setNotificationVisibility(
                    if (preferences.downloadNotifications) {
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    } else {
                        DownloadManager.Request.VISIBILITY_VISIBLE
                    }
                )

                val configuredDir = preferences.downloadDirectory
                if (configuredDir.isNotBlank()) {
                    val target = File(configuredDir, fileName)
                    setDestinationUri(Uri.fromFile(target))
                } else {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                }
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                getString(R.string.download_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openExternalIntent(uri: Uri) {
        val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(externalIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isCurrentTabIncognito(): Boolean = tabManager.current?.incognito == true

    /**
     * True when the WebView is the visible surface. The WebView's own visibility flag stays
     * VISIBLE even while its parent is hidden, so the container is the reliable signal.
     */
    private fun isShowingWeb(): Boolean = binding.swipeRefresh.visibility == View.VISIBLE

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun resolveColor(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
        refreshAdVisibility()
        refreshTabUi()
        if (!isShowingWeb()) {
            refreshHomeContent()
        }
        // Settings may have changed while we were backgrounded.
        applyContentSettingsToTabs()
        AdBlocker.updateEnabled(preferences.adBlockEnabled)
        // KoiBrowser's resumeAll(): timers stay paused for background tabs but the active one
        // needs to resume, otherwise timers never restart after a process pause.
        tabManager.current?.webView?.apply {
            onResume()
            resumeTimers()
        }
        checkClipboardForLink()
    }

    /**
     * Push the content preferences onto every live tab.
     *
     * Tabs pick these up at creation, but the user can change them from Settings and come back,
     * so re-assert them here rather than leaving stale WebViews behind.
     */
    private fun applyContentSettingsToTabs() {
        val javaScript = preferences.javaScriptEnabled
        val images = preferences.imagesEnabled
        val desktop = preferences.desktopMode
        tabManager.all.forEach { session ->
            session.webView.settings.apply {
                javaScriptEnabled = javaScript
                loadsImagesAutomatically = images
                blockNetworkImage = !images
                userAgentString = if (desktop) DESKTOP_USER_AGENT else null
            }
        }
    }

    override fun onPause() {
        bannerAdView?.pause()
        tabManager.current?.webView?.apply {
            pauseTimers()
            onPause()
        }
        super.onPause()
    }

    /** Persist the open tabs so the next launch can restore them (KoiBrowser's saveOpenTabs). */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        persistTabs()
    }

    private fun persistTabs() {
        preferences.savedTabs = tabManager.serialize()
    }

    /**
     * KoiBrowser's clipboard monitor: when enabled, offer to open a link the user just copied.
     * Only fires once per distinct value so it cannot nag.
     */
    private fun checkClipboardForLink() {
        if (!preferences.clipboardMonitor) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank() || text == lastClipboardValue) return
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return
        lastClipboardValue = text
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_in_new_tab)
            .setMessage(getString(R.string.clipboard_link_found, text))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open) { _, _ -> openInNewTab(text, incognito = false) }
            .show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // KoiBrowser's freeMemory(): let background WebViews release caches under pressure.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tabManager.freeMemory()
        }
    }

    override fun onDestroy() {
        IncognitoModeState.isIncognitoActive = false
        persistTabs()
        MediaRegistry.removeListener(pageMediaListener)
        mediaSheet?.dismiss()
        mediaSheet = null
        browserMenu?.dismiss()
        browserMenu = null
        bannerAdView?.destroy()
        bannerAdView = null
        nativeAd?.destroy()
        nativeAd = null
        suggestionsExecutor.shutdownNow()

        // A fullscreen <video> hands us a view that the WebView owns. Detach it and tell Chromium
        // we are done with it *before* the tabs destroy their WebViews, otherwise the video surface
        // is torn down underneath the renderer and the process dies natively.
        if (customView != null) {
            customView = null
            val callback = customViewCallback
            customViewCallback = null
            binding.fullscreenContainer.removeAllViews()
            callback?.onCustomViewHidden()
        }

        tabManager.shutdown()
        super.onDestroy()
    }

    // ==================================================================
    // Video downloader
    // ==================================================================

    /** Page scanner, read from assets once and reused for every tab. */
    private var cachedScannerScript: String? = null

    /** Media capture hook, read from assets once and reused for every tab. */
    private var cachedCaptureScript: String? = null

    /**
     * Install the capture hook before any page script runs.
     *
     * The hook replaces `MediaSource.prototype.addSourceBuffer`, and it has to do so before the
     * player creates its source buffers. Injecting on page finish is always too late when a page
     * is opened directly on a video — YouTube buffers the whole clip before `onPageFinished`
     * fires, so the append hook never sees the initialisation segment and the saved file cannot
     * be decoded. `addDocumentStartJavaScript` runs at document start, which removes the race.
     *
     * Older WebView builds without that API fall back to the page-finish injection in
     * [injectPageMedia], where the hook still catches later streams and Shorts swipes.
     */
    private fun installDocumentStartCapture(webView: WebView) {
        if (!preferences.javaScriptEnabled) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = captureHookScript()
        if (script.isBlank()) return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }.onFailure {
            android.util.Log.w(LOG_TAG, "Document-start capture hook was rejected", it)
        }
    }

    /**
     * Re-inject the page scanner.
     *
     * Navigation replaces the document and takes the previous injection with it, so this has to run
     * on every page finish rather than once per WebView.
     */
    private fun injectPageMedia(webView: WebView?) {
        if (webView == null || !preferences.javaScriptEnabled) return
        val script = pageMediaScript()
        if (script.isNotBlank()) webView.evaluateJavascript(script, null)

        // Fallback for WebView builds without the document-start API; the hook is idempotent, so
        // this costs nothing where it is already installed.
        val capture = captureHookScript()
        if (capture.isNotBlank()) webView.evaluateJavascript(capture, null)
    }

    private fun pageMediaScript(): String {
        cachedScannerScript?.let { return it }
        val script = readAsset(SCANNER_ASSET)
        cachedScannerScript = script
        return script
    }

    private fun captureHookScript(): String {
        cachedCaptureScript?.let { return it }
        val script = readAsset(CAPTURE_ASSET)
        cachedCaptureScript = script
        return script
    }

    private fun readAsset(name: String): String = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        android.util.Log.w(LOG_TAG, "Asset $name could not be read", error)
        ""
    }

    /** The scanner learned something for [tabId]; refresh the button if that is the active tab. */
    private fun onPageMediaUpdated(tabId: Long) {
        val current = tabManager.current ?: return
        if (current.id != tabId) return
        refreshVideoDownloadButton()
    }

    /**
     * The download icon is a signal, not permanent chrome: it only exists while the active tab has
     * something to save.
     */
    private fun refreshVideoDownloadButton() {
        val count = tabManager.current?.let { MediaRegistry.count(it.id) } ?: 0
        // The playing video counts as something to save: on YouTube nothing is ever "detected",
        // yet the video on screen can still be captured.
        val visible = count > 0 || MediaCapture.hasVideo()
        binding.videoDownloadButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showMediaSheet() {
        val session = tabManager.current ?: return

        // YouTube only builds its format list once the player is up, and Shorts replaces the video
        // without reloading the document. Asking the page for the list right now is far more
        // reliable than relying on the collection performed at page load.
        val webView = activeWebView
        if (webView != null && YouTubeExtractor.isWatchUrl(session.url)) {
            webView.evaluateJavascript(
                "window.__extremisCollectFormats && window.__extremisCollectFormats()",
                null
            )
            // The bridge posts back asynchronously, so give it a beat before reading the list.
            webView.postDelayed({
                if (tabManager.current?.id == session.id) openMediaSheet(session)
            }, 450)
            return
        }

        openMediaSheet(session)
    }

    private fun openMediaSheet(session: TabSession) {
        val detected = MediaRegistry.items(session.id)

        // Whatever the player is playing right now goes first: on YouTube every downloadable URL
        // is signature-protected, so this is the only entry that actually works there.
        val items = mutableListOf<MediaItem>()
        if (MediaCapture.hasVideo()) {
            // The container is not known until the capture is muxed — YouTube sends WebM, other
            // sites send MP4 — so the label deliberately does not claim one.
            val bytes = MediaCapture.videoBytes()
            val sizeLabel = when {
                bytes >= 1024L * 1024L ->
                    getString(R.string.captured_size, bytes / (1024L * 1024L))

                bytes > 0L -> getString(R.string.captured_size_kb, bytes / 1024L)
                else -> ""
            }
            items += MediaItem(
                url = CAPTURE_URL,
                kind = MediaKind.DIRECT,
                mimeType = "video",
                title = getString(R.string.playing_video),
                host = "",
                qualityLabel = sizeLabel
            )
        }
        items += detected

        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_media_detected, Toast.LENGTH_SHORT).show()
            return
        }

        val sheetBinding = DialogVideoDownloadBinding.inflate(layoutInflater)
        sheetBinding.mediaSheetSubtitle.text = getString(R.string.streams_found, items.size)

        val adapter = MediaListAdapter { item ->
            mediaSheet?.dismiss()
            downloadMedia(item)
        }
        sheetBinding.mediaList.layoutManager = LinearLayoutManager(this)
        sheetBinding.mediaList.adapter = adapter
        adapter.submit(items)

        val dialog = BottomSheetDialog(this, R.style.Theme_Extremis_BottomSheet)
        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener { mediaSheet = null }
        mediaSheet = dialog
        dialog.show()
        // The behaviour only exists once the dialog is showing; setting the state before show()
        // is silently ignored. Expanded because a page can yield a dozen renditions.
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * Route one detected stream to the right downloader.
     *
     * Single files go to DownloadManager, which already handles pause, resume and notifications.
     * Playlists cannot be joined by DownloadManager, so they go to [MediaDownloadService].
     */
    private fun downloadMedia(item: MediaItem) {
        if (item.url == CAPTURE_URL) {
            saveCapturedVideo()
            return
        }

        val pageUrl = tabManager.current?.url.orEmpty()
        when (item.kind) {
            MediaKind.HLS -> {
                ensureNotificationPermission()
                MediaDownloadService.start(this, item, pageUrl)
                Toast.makeText(this, R.string.hls_download_started, Toast.LENGTH_SHORT).show()
            }

            MediaKind.DASH -> Toast.makeText(
                this,
                R.string.download_unsupported,
                Toast.LENGTH_SHORT
            ).show()

            MediaKind.DIRECT -> enqueueDirectDownload(item, pageUrl)
        }
    }

    /** Progress dialog shown while the page is driven through the whole clip. */
    private var captureDialog: AlertDialog? = null
    private var captureProgressBar: ProgressBar? = null
    private var captureProgressText: TextView? = null
    private var captureFallback: Runnable? = null
    private var captureFinishing = false

    /**
     * Save the video that is on screen — the whole of it.
     *
     * A page only feeds its player the segments it is about to show, so saving straight away keeps
     * just the part the user happened to reach. Instead the page is asked to drive its own player
     * forward (muted, at high speed), which makes it fetch every remaining segment; the append hook
     * collects them as they arrive. The save runs when that drive reports back.
     */
    private fun saveCapturedVideo() {
        // A second tap while a drive is already running must not start another one.
        if (captureDialog?.isShowing == true) return

        val webView = activeWebView
        if (webView == null || !preferences.javaScriptEnabled) {
            finishCapturedSave()
            return
        }

        showCaptureProgress()
        webView.evaluateJavascript(
            "(function(){try{" +
                "if(window.__extremisCaptureFull){window.__extremisCaptureFull();return;}" +
                "ExtremisMedia.onFullCaptureReady('no-hook');" +
                "}catch(e){try{ExtremisMedia.onFullCaptureReady('failed');}catch(e2){}}})()",
            null
        )

        // A page that cannot drive its own player must not leave the dialog up forever.
        val fallback = Runnable { if (captureDialog?.isShowing == true) finishCapturedSave() }
        captureFallback = fallback
        Handler(mainLooper).postDelayed(fallback, CAPTURE_DRIVE_TIMEOUT_MS)
    }

    /**
     * Join the captured tracks and publish them to Downloads.
     *
     * Runs off the main thread: muxing a long video takes a few seconds and would jank the UI.
     */
    private fun finishCapturedSave() {
        if (captureFinishing) return
        captureFinishing = true

        captureFallback?.let { Handler(mainLooper).removeCallbacks(it) }
        captureFallback = null
        dismissCaptureProgress()

        // Nothing worth saving: the player never buffered past the opening segment.
        if (!MediaCapture.hasUsableVideo()) {
            captureFinishing = false
            Toast.makeText(this, R.string.download_failed_short, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, R.string.saving_video, Toast.LENGTH_SHORT).show()
        Thread {
            val name = MediaCapture.saveToDownloads(applicationContext)
            runOnUiThread {
                captureFinishing = false
                refreshVideoDownloadButton()
                val message = if (name != null) {
                    getString(R.string.saved_as, name)
                } else {
                    getString(R.string.download_failed_short)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showCaptureProgress() {
        dismissCaptureProgress()

        val padding = (20 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            text = getString(R.string.capture_progress_starting)
            setPadding(padding, padding, padding, padding / 2)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(padding, 0, padding, padding)
        }
        captureProgressText = label
        captureProgressBar = bar

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar)
        }

        captureDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_full_title)
            .setView(column)
            .setNegativeButton(R.string.capture_stop) { _, _ -> stopFullCapture() }
            .setCancelable(false)
            .show()
    }

    private fun updateCaptureProgress(percent: Int) {
        captureProgressBar?.progress = percent.coerceIn(0, 100)
        captureProgressText?.text = getString(R.string.capture_progress_percent, percent)
    }

    /** Stop driving the player and keep whatever has been collected so far. */
    private fun stopFullCapture() {
        activeWebView?.evaluateJavascript(
            "(function(){try{" +
                "if(window.__extremisStopCapture){window.__extremisStopCapture();return;}" +
                "ExtremisMedia.onFullCaptureReady('stopped');" +
                "}catch(e){}})()",
            null
        )
    }

    private fun dismissCaptureProgress() {
        runCatching { captureDialog?.dismiss() }
        captureDialog = null
        captureProgressBar = null
        captureProgressText = null
    }

    private fun enqueueDirectDownload(item: MediaItem, referer: String) {
        try {
            val fileName = buildMediaFileName(item)
            val request = DownloadManager.Request(item.url.toUri()).apply {
                setMimeType(item.mimeType)
                addRequestHeader("User-Agent", DESKTOP_USER_AGENT)
                // Media CDNs routinely 403 without the page they were requested from.
                if (referer.isNotBlank()) addRequestHeader("Referer", referer)
                CookieManager.getInstance().getCookie(item.url)?.let { cookies ->
                    addRequestHeader("Cookie", cookies)
                }
                setTitle(fileName)
                setDescription(getString(R.string.downloads))
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                getString(R.string.download_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Build a filename from the page title, keeping the extension the mime type implies. */
    private fun buildMediaFileName(item: MediaItem): String {
        // A YouTube title sitting in Downloads is an obvious fingerprint, so those files get a
        // neutral timestamped name instead.
        val base = if (item.source == MediaItem.SOURCE_YOUTUBE) {
            "video_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        } else {
            item.title.ifBlank { item.host }.ifBlank { "video" }
        }
        val cleaned = base.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80)
        val withoutExtension = cleaned.substringBeforeLast('.', cleaned)
        val extension = when {
            item.mimeType.contains("webm") -> "webm"
            item.mimeType.contains("mpeg") -> "mp3"
            item.mimeType.contains("audio") -> "m4a"
            item.mimeType.contains("matroska") -> "mkv"
            else -> "mp4"
        }
        return "$withoutExtension.$extension"
    }

    /** The service runs regardless; without this the user just never sees its progress. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            "android.permission.POST_NOTIFICATIONS"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQUEST_NOTIFICATIONS)
        }
    }

    /**
     * Page-facing bridge for the scanner script.
     *
     * Deliberately tiny and nested rather than inner: page script runs in this process, so the
     * bridge must expose nothing about the activity, and holding no reference to it means a
     * long-lived WebView cannot leak the activity. Both methods take strings and do nothing but
     * feed the scanner.
     */
    private class MediaBridge(private val tabId: Long) {

        @JavascriptInterface
        fun postMedia(url: String?, pageTitle: String?, source: String?) {
            val target = url ?: return
            runCatching { MediaRegistry.offer(tabId, target, pageTitle.orEmpty()) }
        }

        @JavascriptInterface
        fun postPlayerFormats(json: String?) {
            runCatching {
                YouTubeExtractor.parse(json, "").forEach { item ->
                    MediaRegistry.record(tabId, item)
                }
            }
        }

        /**
         * One `SourceBuffer.appendBuffer` payload, base64 encoded.
         *
         * This is called on a WebView background thread for every media chunk the page plays, so
         * it does the least work possible: decode, then append.
         */
        @JavascriptInterface
        fun postMediaChunk(kind: String?, base64: String?) {
            val track = kind ?: return
            val payload = base64 ?: return
            if (track != "video" && track != "audio") return
            runCatching {
                MediaCapture.append(
                    track,
                    android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                )
            }
        }

        /**
         * A new video element started playing, so the previous capture belongs to a different
         * video. YouTube Shorts swaps videos without reloading the document, so navigation alone
         * is not enough to notice this.
         */
        @JavascriptInterface
        fun resetMediaCapture() {
            android.util.Log.i(LOG_TAG, "page asked to reset the capture")
            MediaCapture.clear()
        }

        /** How much of the clip the "collect the whole video" run has gathered, in percent. */
        @JavascriptInterface
        fun onFullCaptureProgress(percent: Int) {
            runCatching { MediaCapture.onFullCaptureProgress?.invoke(percent) }
        }

        /** That run ended; the app saves whatever was collected. */
        @JavascriptInterface
        fun onFullCaptureReady(reason: String?) {
            runCatching { MediaCapture.onFullCaptureReady?.invoke(reason.orEmpty()) }
        }
    }

    private data class QuickLink(val title: String, val url: String) {
        val initial: String = title.firstOrNull { it.isLetterOrDigit() }
            ?.toString()?.uppercase() ?: "?"
    }

    private data class ViewPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun View.capturePadding(): ViewPadding {
        return ViewPadding(
            left = paddingLeft,
            top = paddingTop,
            right = paddingRight,
            bottom = paddingBottom
        )
    }

    companion object {
        private const val LOG_TAG = "ExtremisBrowser"
        private const val MOST_VISITED_COLUMNS = 4
        private const val MAX_MOST_VISITED_BOOKMARKS = 4
        private const val MAX_FEED_ITEMS = 8
        private const val RATE_PROMPT_AFTER_LAUNCHES = 5
        private const val RATE_PROMPT_INTERVAL = 10
        private const val REQUEST_NOTIFICATIONS = 4201

        /** Name the page scanner uses to reach [MediaBridge]. */
        private const val JS_BRIDGE_NAME = "ExtremisMedia"

        /** Bundled page scanner, see assets/page_media.js. */
        private const val SCANNER_ASSET = "page_media.js"

        /** Bundled capture hook, see assets/media_capture.js. */
        private const val CAPTURE_ASSET = "media_capture.js"

        /**
         * Pseudo-URL marking the "save what is playing" entry in the media sheet.
         *
         * It is never fetched — [downloadMedia] recognises it and hands off to [MediaCapture]
         * instead of the network.
         */
        private const val CAPTURE_URL = "extremis-capture://playing"

        /**
         * Backstop for a download that never reports back. The page stops itself when it stalls or
         * reaches the end, and playback runs at normal speed, so this only has to be generous
         * enough for a very long video.
         */
        private const val CAPTURE_DRIVE_TIMEOUT_MS = 3 * 60 * 60 * 1000L + 5 * 60 * 1000L

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"

        fun createLaunchIntent(context: Context, url: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                data = Uri.parse(url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}
