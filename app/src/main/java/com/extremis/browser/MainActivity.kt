package com.extremis.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.extremis.browser.databinding.ActivityMainBinding
import com.extremis.browser.models.Bookmark
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.models.VideoInfo
import com.extremis.browser.models.VideoQuality
import com.extremis.browser.utils.DatabaseHelper
import com.extremis.browser.utils.UrlParser
import com.extremis.browser.utils.VideoDownloader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var urlParser: UrlParser
    private lateinit var videoDownloader: VideoDownloader

    private val homeUrl = "https://www.google.com"
    private val facebookUrl = "https://m.facebook.com"
    private val youtubeUrl = "https://m.youtube.com"

    private var isDesktopMode = false
    private var currentPageTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)
        urlParser = UrlParser()
        videoDownloader = VideoDownloader(this)

        setupWebView()
        setupUi()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            // Handle incoming URL intent, or load home
            val intentUrl = intent?.data?.toString()
            loadUrl(intentUrl ?: homeUrl)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { loadUrl(it) }
    }

    // ── WebView setup ───────────────────────────────────────────────────

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
                view: WebView?, request: WebResourceRequest?
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
                binding.urlInput.setText(url.orEmpty())
                binding.fabVideoDownload.visibility = View.GONE
                updateBrowserUi(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.hide()
                binding.swipeRefresh.isRefreshing = false
                binding.urlInput.setText(url.orEmpty())
                updateBrowserUi(url)

                // Save to history
                if (!url.isNullOrEmpty() && !url.startsWith("about:")) {
                    val title = currentPageTitle.ifBlank { url }
                    databaseHelper.addToHistory(HistoryItem(title = title, url = url))
                }

                // Check for downloadable videos on page
                checkForVideos(url.orEmpty())
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.hide()
                } else {
                    binding.progressBar.show()
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                currentPageTitle = title ?: ""
                val fallbackHost = view?.url?.let { Uri.parse(it).host }.orEmpty()
                binding.topBar.subtitle =
                    title?.takeUnless { it.isBlank() } ?: fallbackHost
            }
        }

        binding.webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                handleDownload(url, userAgent, contentDisposition, mimeType)
            }
        )
    }

    // ── UI setup ────────────────────────────────────────────────────────

    private fun setupUi() {
        binding.goButton.setOnClickListener { loadFromInput() }
        binding.homeButton.setOnClickListener { loadUrl(homeUrl) }
        binding.facebookButton.setOnClickListener { loadUrl(facebookUrl) }
        binding.youtubeButton.setOnClickListener { loadUrl(youtubeUrl) }
        binding.downloadsButton.setOnClickListener { openSystemDownloads() }
        binding.shareButton.setOnClickListener { shareCurrentPage() }
        binding.desktopModeButton.setOnClickListener { toggleDesktopMode() }
        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.reloadButton.setOnClickListener { binding.webView.reload() }

        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            val shouldGo = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (shouldGo) { loadFromInput(); true } else false
        }

        // Swipe-to-refresh
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefresh.setColorSchemeResources(R.color.extremis_blue)

        // Video download FAB
        binding.fabVideoDownload.setOnClickListener { detectAndShowDownloadDialog() }

        updateBrowserUi(homeUrl)
    }

    // ── URL loading ─────────────────────────────────────────────────────

    private fun loadFromInput() {
        val rawInput = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (rawInput.isEmpty()) return

        val target = when {
            rawInput.startsWith("http://", ignoreCase = true) ||
                    rawInput.startsWith("https://", ignoreCase = true) -> rawInput
            rawInput.contains(".") && !rawInput.contains(" ") -> "https://$rawInput"
            else -> "https://www.google.com/search?q=${Uri.encode(rawInput)}"
        }

        loadUrl(target)
    }

    private fun loadUrl(url: String) {
        binding.webView.loadUrl(url)
    }

    // ── Video detection ─────────────────────────────────────────────────

    private fun checkForVideos(currentUrl: String) {
        // Show the FAB if this is a known video platform page or if
        // the URL itself is a direct video link
        if (urlParser.isVideoUrl(currentUrl)) {
            binding.fabVideoDownload.visibility = View.VISIBLE
            return
        }

        // Try JS-based detection of <video> elements in the page
        binding.webView.evaluateJavascript(
            """
            (function() {
                var videos = document.getElementsByTagName('video');
                return videos.length > 0;
            })();
            """.trimIndent()
        ) { result ->
            if (result == "true") {
                binding.fabVideoDownload.visibility = View.VISIBLE
            }
        }
    }

    private fun detectAndShowDownloadDialog() {
        val currentUrl = binding.webView.url ?: return

        // Direct video file link → immediate dialog
        if (currentUrl.matches(
                Regex(
                    ".*\\.(mp4|webm|ogg|mov|avi|mkv|flv|3gp|m4v)(\\?.*)?$",
                    RegexOption.IGNORE_CASE
                )
            )
        ) {
            showDownloadDialog(
                VideoInfo(
                    title = currentUrl.substringAfterLast("/").substringBefore("?"),
                    url = currentUrl,
                    qualities = listOf(
                        VideoQuality("Original Quality", currentUrl, "720", "MP4")
                    )
                )
            )
            return
        }

        // Inject JS to extract <video> / <source> elements from the page
        binding.webView.evaluateJavascript(
            """
            (function() {
                var videos = document.getElementsByTagName('video');
                var sources = [];
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    var ss = v.getElementsByTagName('source');
                    if (ss.length > 0) {
                        for (var j = 0; j < ss.length; j++) {
                            sources.push({src: ss[j].src, type: ss[j].type || 'video/mp4',
                                          width: v.videoWidth || 0, height: v.videoHeight || 0});
                        }
                    } else if (v.src) {
                        sources.push({src: v.src, type: v.type || 'video/mp4',
                                      width: v.videoWidth || 0, height: v.videoHeight || 0});
                    }
                }
                return JSON.stringify(sources);
            })();
            """.trimIndent()
        ) { result ->
            handleJsVideoResult(result, currentUrl)
        }
    }

    private fun handleJsVideoResult(result: String, currentUrl: String) {
        val videoInfo = urlParser.parseVideoUrls(result, currentUrl)

        if (videoInfo != null && videoInfo.qualities.isNotEmpty()) {
            showDownloadDialog(videoInfo)
        } else {
            // Fallback: pass the page URL and let VideoDownloader
            // extract qualities via OkHttp / server-side parsing
            showDownloadDialog(
                VideoInfo(
                    title = currentPageTitle.ifBlank { urlParser.extractTitle(currentUrl) },
                    url = currentUrl,
                    qualities = emptyList()  // dialog will fetch
                )
            )
        }
    }

    private fun showDownloadDialog(videoInfo: VideoInfo) {
        VideoDownloadDialogFragment.newInstance(videoInfo)
            .show(supportFragmentManager, "VideoDownloadDialog")
    }

    // ── Downloads ───────────────────────────────────────────────────────

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        enqueueDownload(
            url = url,
            userAgent = userAgent.orEmpty(),
            contentDisposition = contentDisposition.orEmpty(),
            mimeType = mimeType.orEmpty()
        )
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (mimeType.isNotBlank()) setMimeType(mimeType)
                if (userAgent.isNotBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { cookies ->
                    addRequestHeader("Cookie", cookies)
                }
                setTitle(fileName)
                setDescription("Downloading $fileName")
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                )
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Download failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Share ───────────────────────────────────────────────────────────

    private fun shareCurrentPage() {
        val url = binding.webView.url ?: return
        val title = currentPageTitle.ifBlank { "Check this out" }
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, "Share via"))
    }

    // ── Desktop mode ────────────────────────────────────────────────────

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        val settings = binding.webView.settings

        settings.userAgentString = if (isDesktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            WebSettings.getDefaultUserAgent(this)
        }
        settings.useWideViewPort = isDesktopMode
        settings.loadWithOverviewMode = isDesktopMode

        binding.desktopModeButton.text = if (isDesktopMode) "Mobile" else "Desktop"
        binding.webView.reload()

        Toast.makeText(
            this,
            if (isDesktopMode) "Desktop mode" else "Mobile mode",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── Browser UI helpers ──────────────────────────────────────────────

    private fun updateBrowserUi(currentUrl: String?) {
        binding.backButton.isEnabled = binding.webView.canGoBack()
        binding.forwardButton.isEnabled = binding.webView.canGoForward()

        if (binding.urlInput.text?.toString() != currentUrl.orEmpty()) {
            binding.urlInput.setText(currentUrl.orEmpty())
        }

        val host = currentUrl?.let { Uri.parse(it).host }.orEmpty()
        if (binding.topBar.subtitle.isNullOrBlank()) {
            binding.topBar.subtitle = host.ifBlank { "Direct file downloads only" }
        }
    }

    private fun openSystemDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Downloads app is not available", Toast.LENGTH_SHORT).show()
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

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        binding.webView.apply {
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
