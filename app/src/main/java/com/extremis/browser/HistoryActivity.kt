package com.extremis.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.HistoryAdapter
import com.extremis.browser.databinding.ActivityHistoryBinding
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.utils.AdHelper
import com.extremis.browser.utils.DatabaseHelper
import com.google.android.gms.ads.AdView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Chrome-style history screen: search field, a "Clear all" action, and a flat list with
 * favicons and relative timestamps.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: HistoryAdapter
    private var allHistory: List<HistoryItem> = emptyList()
    private var bannerAdView: AdView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        binding.toolbar.title = getString(R.string.history)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = HistoryAdapter(
            onOpen = ::openHistoryItem,
            onMenu = ::showItemMenu
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        AdHelper.loadBannerAd(this, binding.adViewContainer) { adView ->
            bannerAdView = adView
        }

        binding.clearButton.setOnClickListener { confirmClearHistory() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
        loadHistory()
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

    private fun loadHistory() {
        allHistory = databaseHelper.getHistory()
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val cleanQuery = query.trim()
        val filtered = if (cleanQuery.isBlank()) {
            allHistory
        } else {
            allHistory.filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                    it.url.contains(cleanQuery, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openHistoryItem(item: HistoryItem) {
        startActivity(MainActivity.createLaunchIntent(this, item.url))
        finish()
    }

    private fun showItemMenu(item: HistoryItem, anchor: View) {
        val options = arrayOf(
            getString(R.string.open),
            getString(R.string.copy_link),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title.ifBlank { item.url })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openHistoryItem(item)
                    1 -> copyToClipboard(item.url)
                    else -> deleteHistoryItem(item)
                }
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                databaseHelper.clearHistory()
                loadHistory()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun deleteHistoryItem(item: HistoryItem) {
        databaseHelper.deleteHistoryItem(item.id)
        loadHistory()
        Toast.makeText(this, R.string.history_item_removed, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }
}
