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
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.recyclerview.widget.GridLayoutManager
import com.extremis.browser.adapters.HomeBookmarksAdapter
import com.extremis.browser.databinding.ActivityMainBinding
import com.extremis.browser.models.Bookmark
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.utils.DatabaseHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var homeBookmarksAdapter: HomeBookmarksAdapter

    private val homeUrl = "https://www.google.com"

    private var isDesktopMode = false
    private var currentPageTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        setupWebView()
        setupHomeBookmarks()
        setupUi()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
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
                binding.urlInput.setText(url.orEmpty())
                updateBrowserUi(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.hide()
                binding.swipeRefresh.isRefreshing = false
                binding.urlInput.setText(url.orEmpty())
                updateBrowserUi(url)

                if (!url.isNullOrEmpty() && !url.startsWith("about:")) {
                    val title = currentPageTitle.ifBlank { url }
                    databaseHelper.addToHistory(HistoryItem(title = title, url = url))
                }
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
                val fallbackHost = view?.url?.let { it.toUri().host }.orEmpty()
                binding.topBar.subtitle = title?.takeUnless { it.isBlank() } ?: fallbackHost
            }
        }

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupHomeBookmarks() {
        homeBookmarksAdapter = HomeBookmarksAdapter(
            onOpen = { loadUrl(it.url) },
            onAddBookmark = {
                val url = binding.webView.url
                if (!url.isNullOrEmpty() && url != homeUrl) {
                    databaseHelper.addBookmark(Bookmark(title = currentPageTitle, url = url))
                    loadHomeBookmarks()
                    Toast.makeText(this, "Bookmark added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Open a page first to bookmark it", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        )

        binding.homeBookmarksRecycler.layoutManager = GridLayoutManager(this, 4)
        binding.homeBookmarksRecycler.adapter = homeBookmarksAdapter

        binding.homeBookmarksManageButton.setOnClickListener {
            startActivity(Intent(this, BookmarksActivity::class.java))
        }

        loadHomeBookmarks()
    }

    private fun loadHomeBookmarks() {
        val bookmarks = databaseHelper.getBookmarks()
        homeBookmarksAdapter.submitList(bookmarks)
    }

    private fun setupUi() {
        binding.goButton.setOnClickListener { loadFromInput() }
        binding.homeButton.setOnClickListener { loadUrl(homeUrl) }

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
            if (shouldGo) {
                loadFromInput()
                true
            } else {
                false
            }
        }

        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefresh.setColorSchemeResources(R.color.extremis_blue)

        binding.topBar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.topBar.setOnClickListener { showMenu(it) }

        updateBrowserUi(homeUrl)
    }

    private fun showMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_save_bookmark -> {
                    val url = binding.webView.url
                    if (!url.isNullOrEmpty()) {
                        databaseHelper.addBookmark(Bookmark(title = currentPageTitle, url = url))
                        loadHomeBookmarks()
                        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                R.id.action_bookmarks -> {
                    startActivity(Intent(this, BookmarksActivity::class.java))
                    true
                }

                R.id.action_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }

                R.id.action_downloads -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    true
                }

                R.id.action_system_downloads -> {
                    openSystemDownloads()
                    true
                }

                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

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
            val request = DownloadManager.Request(url.toUri()).apply {
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
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Download failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

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

        binding.webView.reload()

        Toast.makeText(
            this,
            if (isDesktopMode) "Desktop mode" else "Mobile mode",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateBrowserUi(currentUrl: String?) {
        binding.backButton.isEnabled = binding.webView.canGoBack()
        binding.forwardButton.isEnabled = binding.webView.canGoForward()

        if (binding.urlInput.text?.toString() != currentUrl.orEmpty()) {
            binding.urlInput.setText(currentUrl.orEmpty())
        }

        val host = currentUrl?.let { it.toUri().host }.orEmpty()
        if (binding.topBar.subtitle.isNullOrBlank()) {
            binding.topBar.subtitle = host
        }

        if (currentUrl == homeUrl || currentUrl == "about:blank") {
            binding.homeBookmarksContainer.visibility = View.VISIBLE
            loadHomeBookmarks()
        } else {
            binding.homeBookmarksContainer.visibility = View.GONE
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

    override fun onResume() {
        super.onResume()
        if (binding.webView.url == homeUrl || binding.webView.url == "about:blank") {
            loadHomeBookmarks()
        }
    }

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

    companion object {
        fun createLaunchIntent(context: Context, url: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                data = Uri.parse(url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}
