package com.extremis.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.extremis.browser.databinding.ActivitySettingsBinding
import com.extremis.browser.databinding.ItemSettingsRowBinding
import com.extremis.browser.utils.AdBlocker
import com.extremis.browser.utils.BrowserPreferences
import com.extremis.browser.utils.DatabaseHelper
import com.extremis.browser.utils.FaviconLoader
import com.extremis.browser.utils.FileUtils
import com.extremis.browser.utils.SearchEngine
import com.extremis.browser.utils.AdHelper
import com.google.android.gms.ads.AdView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Chrome-style settings screen: grouped rows with a leading icon, a title and an optional
 * summary line. Rows are built programmatically so sections stay easy to reorder.
 *
 * The setting set is the one KoiBrowser exposed in its `SettingsBottomSheetFragment` +
 * `bottom_settings.xml`, resurfaced in our Chrome list style.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var preferences: BrowserPreferences
    private var bannerAdView: AdView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)
        preferences = BrowserPreferences.get(this)

        binding.toolbar.title = getString(R.string.settings)
        binding.toolbar.setNavigationOnClickListener { finish() }

        AdHelper.loadBannerAd(this, binding.adViewContainer) { adView ->
            bannerAdView = adView
        }

        buildSettings()
    }

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
    }

    override fun onPause() {
        bannerAdView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        bannerAdView?.destroy()
        bannerAdView = null
        super.onDestroy()
    }

    private fun buildSettings() {
        val container = binding.settingsContainer
        container.removeAllViews()

        addSection(R.string.settings_search)
        addRow(
            R.drawable.ic_search_24,
            R.string.search_engine,
            summary = preferences.searchEngine.label
        ) { chooseSearchEngine() }
        addSwitch(
            R.drawable.ic_search_24,
            R.string.search_suggestions,
            R.string.search_suggestions_summary,
            { preferences.searchSuggestions },
            { preferences.searchSuggestions = it }
        )
        addSwitch(
            R.drawable.ic_history_24,
            R.string.save_search_history,
            null,
            { preferences.saveSearchHistory },
            { preferences.saveSearchHistory = it }
        )

        addSection(R.string.settings_content)
        addSwitch(
            R.drawable.ic_globe_24,
            R.string.java_script,
            R.string.java_script_summary,
            { preferences.javaScriptEnabled },
            { preferences.javaScriptEnabled = it }
        )
        addSwitch(
            R.drawable.ic_globe_24,
            R.string.load_images,
            R.string.load_images_summary,
            { preferences.imagesEnabled },
            { preferences.imagesEnabled = it }
        )
        addSwitch(
            R.drawable.ic_new_window_24,
            R.string.desktop_site,
            null,
            { preferences.desktopMode },
            { enabled ->
                preferences.desktopMode = enabled
                toast(if (enabled) R.string.desktop_site_on else R.string.desktop_site_off)
            }
        )
        addSwitch(
            R.drawable.ic_new_window_24,
            R.string.block_popups,
            R.string.block_popups_summary,
            { preferences.blockPopups },
            { preferences.blockPopups = it }
        )

        addSection(R.string.settings_privacy)
        addSwitch(
            R.drawable.ic_delete_sweep_24,
            R.string.ad_blocking,
            R.string.ad_blocking_summary,
            { preferences.adBlockEnabled },
            { enabled ->
                preferences.adBlockEnabled = enabled
                AdBlocker.updateEnabled(enabled)
            }
        )
        addRow(
            R.drawable.ic_delete_sweep_24,
            R.string.blocked_requests,
            summary = getString(R.string.blocked_requests, AdBlocker.count.toInt())
        )
        addSwitch(
            R.drawable.ic_incognito_24,
            R.string.incognito_home,
            null,
            { preferences.incognitoEnabled },
            { preferences.incognitoEnabled = it }
        )
        addSwitch(
            R.drawable.ic_add_24,
            R.string.clipboard_monitor,
            R.string.clipboard_monitor_summary,
            { preferences.clipboardMonitor },
            { preferences.clipboardMonitor = it }
        )
        addSwitch(
            R.drawable.ic_recent_tabs_24,
            R.string.restore_tabs,
            R.string.restore_tabs_summary,
            { preferences.restoreTabs },
            { preferences.restoreTabs = it }
        )

        addSection(R.string.settings_library)
        addRow(R.drawable.ic_bookmark_24, R.string.bookmarks) {
            startActivity(Intent(this, BookmarksActivity::class.java))
        }
        addRow(R.drawable.ic_history_24, R.string.history) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        addRow(R.drawable.ic_download_24, R.string.downloads) {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        addRow(R.drawable.ic_new_window_24, R.string.system_downloads) {
            openSystemDownloads()
        }

        addSection(R.string.settings_advanced)
        addRow(
            R.drawable.ic_folder_24,
            R.string.choose_folder,
            summary = downloadDirectorySummary()
        ) { chooseDownloadFolder() }
        addSwitch(
            R.drawable.ic_download_24,
            R.string.download_notifications,
            R.string.download_notifications_summary,
            { preferences.downloadNotifications },
            { preferences.downloadNotifications = it }
        )
        addSwitch(
            R.drawable.ic_download_24,
            R.string.download_wifi_only,
            R.string.download_wifi_only_summary,
            { preferences.downloadWifiOnly },
            { preferences.downloadWifiOnly = it }
        )

        addSection(R.string.settings_browsing)
        addRow(R.drawable.ic_delete_sweep_24, R.string.clear_history) {
            confirm(
                titleRes = R.string.clear_history,
                messageRes = null,
                onConfirm = {
                    databaseHelper.clearHistory()
                    toast(R.string.history_cleared)
                }
            )
        }
        addRow(R.drawable.ic_delete_24, R.string.clear_bookmarks) {
            confirm(
                titleRes = R.string.clear_bookmarks,
                messageRes = null,
                onConfirm = {
                    databaseHelper.clearBookmarks()
                    toast(R.string.bookmarks_cleared)
                }
            )
        }
        addRow(
            R.drawable.ic_delete_sweep_24,
            R.string.delete_browsing_data,
            summaryRes = R.string.delete_browsing_data_confirm
        ) {
            confirm(
                titleRes = R.string.delete_browsing_data,
                messageRes = R.string.delete_browsing_data_confirm,
                onConfirm = {
                    databaseHelper.clearHistory()
                    FaviconLoader.clear()
                    toast(R.string.browsing_data_deleted)
                }
            )
        }

        addSection(R.string.settings_general)
        addRow(
            R.drawable.ic_info_24,
            R.string.app_name,
            summary = appVersion()
        )
        addRow(R.drawable.ic_help_24, R.string.help_feedback) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://support.google.com/chrome".toUri()))
        }
    }

    /** KoiBrowser's SearchEngineSetUp() picker. */
    private fun chooseSearchEngine() {
        val engines = SearchEngine.entries
        val labels = engines.map { it.label }.toTypedArray()
        val current = engines.indexOf(preferences.searchEngine)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                preferences.searchEngine = engines[which]
                dialog.dismiss()
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseDownloadFolder() {
        folderPickerLauncher.launch(FileBrowserActivity.pickFolderIntent(this))
    }

    private val folderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
        if (result.resultCode == RESULT_OK && !path.isNullOrBlank()) {
            preferences.downloadDirectory = path
            toast(R.string.folder_selected)
            buildSettings()
        }
    }

    private fun downloadDirectorySummary(): String {
        return preferences.downloadDirectory.ifBlank {
            FileUtils.defaultDownloadDirectory(this, preferences).absolutePath
        }
    }

    private fun addSection(titleRes: Int) {
        binding.settingsContainer.addView(
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setText(titleRes)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.chrome_blue))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
    }

    private fun addRow(
        iconRes: Int,
        titleRes: Int,
        summaryRes: Int? = null,
        summary: String? = null,
        onClick: (() -> Unit)? = null
    ) {
        val rowBinding = ItemSettingsRowBinding.inflate(
            layoutInflater,
            binding.settingsContainer,
            false
        )
        rowBinding.rowIcon.setImageResource(iconRes)
        rowBinding.rowTitle.setText(titleRes)

        val summaryText = summary ?: summaryRes?.let { getString(it) }
        if (summaryText.isNullOrBlank()) {
            rowBinding.rowSummary.visibility = View.GONE
        } else {
            rowBinding.rowSummary.visibility = View.VISIBLE
            rowBinding.rowSummary.text = summaryText
        }

        if (onClick == null) {
            rowBinding.root.isClickable = false
            rowBinding.root.isFocusable = false
            rowBinding.rowChevron.visibility = View.GONE
        } else {
            rowBinding.root.setOnClickListener { onClick() }
        }

        binding.settingsContainer.addView(rowBinding.root)
    }

    /**
     * A row with a trailing switch. Tapping anywhere on the row toggles it, matching the way
     * KoiBrowser's `bottom_settings.xml` switches behaved.
     */
    private fun addSwitch(
        iconRes: Int,
        titleRes: Int,
        summaryRes: Int?,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit
    ) {
        val rowBinding = ItemSettingsRowBinding.inflate(
            layoutInflater,
            binding.settingsContainer,
            false
        )
        rowBinding.rowIcon.setImageResource(iconRes)
        rowBinding.rowTitle.setText(titleRes)
        rowBinding.rowChevron.visibility = View.GONE

        val summaryText = summaryRes?.let { getString(it) }
        if (summaryText.isNullOrBlank()) {
            rowBinding.rowSummary.visibility = View.GONE
        } else {
            rowBinding.rowSummary.visibility = View.VISIBLE
            rowBinding.rowSummary.text = summaryText
        }

        val switch = rowBinding.rowSwitch
        switch.visibility = View.VISIBLE
        switch.isChecked = getter()

        switch.setOnCheckedChangeListener { _, checked -> setter(checked) }
        rowBinding.root.setOnClickListener {
            // Flipping the switch triggers the listener above exactly once.
            switch.isChecked = !switch.isChecked
        }

        binding.settingsContainer.addView(rowBinding.root)
    }

    private fun confirm(titleRes: Int, messageRes: Int?, onConfirm: () -> Unit) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
        messageRes?.let { builder.setMessage(it) }
        builder.show()
    }

    private fun openSystemDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.downloads_app_unavailable)
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
