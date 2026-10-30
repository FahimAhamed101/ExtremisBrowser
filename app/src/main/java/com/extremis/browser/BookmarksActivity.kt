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
import com.extremis.browser.adapters.BookmarksAdapter
import com.extremis.browser.databinding.ActivityBookmarksBinding
import com.extremis.browser.databinding.DialogAddBookmarkBinding
import com.extremis.browser.models.Bookmark
import com.extremis.browser.utils.AdHelper
import com.extremis.browser.utils.DatabaseHelper
import com.google.android.gms.ads.AdView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Chrome-style bookmarks screen: search field, flat list with favicons, and a per-row menu
 * offering open / edit / copy / delete.
 */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: BookmarksAdapter
    private var allBookmarks: List<Bookmark> = emptyList()
    private var bannerAdView: AdView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        binding.toolbar.title = getString(R.string.bookmarks)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = BookmarksAdapter(
            onOpen = ::openBookmark,
            onMenu = ::showItemMenu
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        AdHelper.loadBannerAd(this, binding.adViewContainer) { adView ->
            bannerAdView = adView
        }

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
        loadBookmarks()
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

    private fun loadBookmarks() {
        allBookmarks = databaseHelper.getBookmarks()
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val cleanQuery = query.trim()
        val filtered = if (cleanQuery.isBlank()) {
            allBookmarks
        } else {
            allBookmarks.filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                    it.url.contains(cleanQuery, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openBookmark(bookmark: Bookmark) {
        startActivity(MainActivity.createLaunchIntent(this, bookmark.url))
        finish()
    }

    private fun showItemMenu(bookmark: Bookmark, anchor: View) {
        val options = arrayOf(
            getString(R.string.open),
            getString(R.string.edit),
            getString(R.string.copy_link),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(bookmark.title.ifBlank { bookmark.url })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openBookmark(bookmark)
                    1 -> showEditDialog(bookmark)
                    2 -> copyToClipboard(bookmark.url)
                    else -> deleteBookmark(bookmark)
                }
            }
            .show()
    }

    private fun showEditDialog(bookmark: Bookmark) {
        val dialogBinding = DialogAddBookmarkBinding.inflate(layoutInflater)
        dialogBinding.bookmarkTitleInput.setText(bookmark.title)
        dialogBinding.bookmarkUrlInput.setText(bookmark.url)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_bookmark_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = dialogBinding.bookmarkUrlInput.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    Toast.makeText(this, R.string.bookmark_url_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val title = dialogBinding.bookmarkTitleInput.text?.toString()?.trim().orEmpty()
                databaseHelper.updateBookmark(
                    bookmark.id,
                    title.ifBlank { url },
                    normalizeUrl(url)
                )
                loadBookmarks()
                Toast.makeText(this, R.string.bookmark_updated, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun deleteBookmark(bookmark: Bookmark) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_bookmark)
            .setMessage(bookmark.title.ifBlank { bookmark.url })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                databaseHelper.deleteBookmark(bookmark.id)
                loadBookmarks()
                Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun normalizeUrl(rawInput: String): String {
        return when {
            rawInput.startsWith("http://", ignoreCase = true) ||
                rawInput.startsWith("https://", ignoreCase = true) -> rawInput

            rawInput.contains(".") && !rawInput.contains(" ") -> "https://$rawInput"
            else -> rawInput
        }
    }
}
