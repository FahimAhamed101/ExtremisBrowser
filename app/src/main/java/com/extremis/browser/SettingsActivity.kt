package com.extremis.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.extremis.browser.databinding.ActivitySettingsBinding
import com.extremis.browser.utils.DatabaseHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var databaseHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        binding.bookmarksButton.setOnClickListener {
            startActivity(Intent(this, BookmarksActivity::class.java))
        }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.downloadsButton.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        binding.systemDownloadsButton.setOnClickListener {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.downloads_app_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        binding.clearHistoryButton.setOnClickListener {
            databaseHelper.clearHistory()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
        binding.clearBookmarksButton.setOnClickListener {
            databaseHelper.clearBookmarks()
            Toast.makeText(this, R.string.bookmarks_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
