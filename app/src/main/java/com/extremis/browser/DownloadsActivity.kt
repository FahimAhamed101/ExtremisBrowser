package com.extremis.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.DownloadsAdapter
import com.extremis.browser.databinding.ActivityDownloadsBinding
import com.extremis.browser.models.DownloadItem
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.downloads)

        adapter = DownloadsAdapter(::openDownload)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.refreshButton.setOnClickListener { loadDownloads() }
        binding.systemButton.setOnClickListener {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.downloads_app_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadDownloads() {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_FAILED or
                DownloadManager.STATUS_PAUSED or
                DownloadManager.STATUS_PENDING or
                DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_SUCCESSFUL
        )

        val items = mutableListOf<DownloadItem>()
        downloadManager.query(query).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val uriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val localUriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val totalSizeIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val lastModifiedIndex =
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

            while (cursor.moveToNext()) {
                val sourceUrl = cursor.getString(uriIndex).orEmpty()
                val localUri = cursor.getString(localUriIndex).orEmpty()
                val title = cursor.getString(titleIndex).orEmpty().ifBlank {
                    Uri.parse(sourceUrl).lastPathSegment ?: getString(R.string.downloads)
                }

                items.add(
                    DownloadItem(
                        id = cursor.getLong(idIndex),
                        title = title,
                        sourceUrl = sourceUrl,
                        localUri = localUri,
                        status = cursor.getInt(statusIndex),
                        totalBytes = cursor.getLong(totalSizeIndex),
                        lastModified = cursor.getLong(lastModifiedIndex)
                    )
                )
            }
        }

        val sortedItems = items.sortedByDescending { it.lastModified }
        adapter.submitList(sortedItems)
        binding.emptyView.visibility = if (sortedItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, R.string.download_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        val targetUri = when {
            item.localUri.startsWith("content://") -> Uri.parse(item.localUri)
            item.localUri.startsWith("file://") || item.localUri.startsWith("/") -> {
                val filePath = if (item.localUri.startsWith("file://")) {
                    Uri.parse(item.localUri).path.orEmpty()
                } else {
                    item.localUri
                }
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
                    return
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }

            else -> null
        }

        if (targetUri == null) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, resolveMimeType(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveMimeType(item: DownloadItem): String {
        val candidate = if (item.localUri.isNotBlank()) item.localUri else item.sourceUrl
        val extension = MimeTypeMap.getFileExtensionFromUrl(candidate).orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
    }
}
