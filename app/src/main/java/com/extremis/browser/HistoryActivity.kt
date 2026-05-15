package com.extremis.browser

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.HistoryAdapter
import com.extremis.browser.databinding.ActivityHistoryBinding
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.utils.DatabaseHelper

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history)

        adapter = HistoryAdapter(
            onOpen = ::openHistoryItem,
            onDelete = ::deleteHistoryItem
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.refreshButton.setOnClickListener { loadHistory() }
        binding.clearButton.setOnClickListener {
            databaseHelper.clearHistory()
            loadHistory()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadHistory() {
        val history = databaseHelper.getHistory()
        adapter.submitList(history)
        binding.emptyView.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openHistoryItem(item: HistoryItem) {
        startActivity(MainActivity.createLaunchIntent(this, item.url))
        finish()
    }

    private fun deleteHistoryItem(item: HistoryItem) {
        databaseHelper.deleteHistoryItem(item.id)
        loadHistory()
        Toast.makeText(this, R.string.history_item_removed, Toast.LENGTH_SHORT).show()
    }
}
