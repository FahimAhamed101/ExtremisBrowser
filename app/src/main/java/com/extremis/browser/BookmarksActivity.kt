package com.extremis.browser

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.BookmarksAdapter
import com.extremis.browser.databinding.ActivityBookmarksBinding
import com.extremis.browser.models.Bookmark
import com.extremis.browser.utils.DatabaseHelper

class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: BookmarksAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.bookmarks)

        adapter = BookmarksAdapter(
            onOpen = ::openBookmark,
            onDelete = ::deleteBookmark
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.refreshButton.setOnClickListener { loadBookmarks() }
        binding.clearButton.setOnClickListener {
            databaseHelper.clearBookmarks()
            loadBookmarks()
            Toast.makeText(this, R.string.bookmarks_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadBookmarks()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadBookmarks() {
        val bookmarks = databaseHelper.getBookmarks()
        adapter.submitList(bookmarks)
        binding.emptyView.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openBookmark(bookmark: Bookmark) {
        startActivity(MainActivity.createLaunchIntent(this, bookmark.url))
        finish()
    }

    private fun deleteBookmark(bookmark: Bookmark) {
        databaseHelper.deleteBookmark(bookmark.id)
        loadBookmarks()
        Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
    }
}
