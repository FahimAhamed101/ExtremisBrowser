package com.extremis.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.DownloadsAdapter
import com.extremis.browser.databinding.ActivityDownloadsBinding
import com.extremis.browser.models.DownloadItem
import com.extremis.browser.utils.AdHelper
import com.google.android.gms.ads.AdView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Chrome-style downloads screen.
 *
 * It merges two sources, because the app writes files two different ways:
 *
 *  - [DownloadManager] — ordinary `<a download>` links and the video downloader's single-file
 *    (mp4 / webm / mp3) path. Querying it is the only way to see running progress.
 *  - MediaStore — playlist (HLS) downloads, which [com.extremis.browser.media.MediaDownloadService]
 *    joins and then publishes into `Download/ExtremisBrowser/`. These never appear in
 *    [DownloadManager], which is why downloaded videos used to be missing from this screen.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var adapter: DownloadsAdapter
    private var bannerAdView: AdView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        binding.toolbar.title = getString(R.string.downloads)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = DownloadsAdapter(
            onOpen = ::openDownload,
            onMenu = ::showItemMenu
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        AdHelper.loadBannerAd(this, binding.adViewContainer) { adView ->
            bannerAdView = adView
        }

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
        bannerAdView?.resume()
        loadDownloads()
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

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private fun loadDownloads() {
        val items = mutableListOf<DownloadItem>()
        items += queryDownloadManager()

        // Files already listed by DownloadManager must not be listed twice. DownloadManager hands
        // back content:// URIs, and so does MediaStore, so the URI is a reliable identity.
        val known = items.mapTo(mutableSetOf()) { it.localUri }
        items += queryPublishedDownloads().filterNot { it.localUri in known }

        val sortedItems = items.sortedByDescending { it.lastModified }
        adapter.submitList(sortedItems)
        binding.emptyView.visibility = if (sortedItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun queryDownloadManager(): List<DownloadItem> {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_FAILED or
                DownloadManager.STATUS_PAUSED or
                DownloadManager.STATUS_PENDING or
                DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_SUCCESSFUL
        )

        val items = mutableListOf<DownloadItem>()
        try {
            downloadManager.query(query).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val uriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                val localUriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val totalSizeIndex =
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val downloadedIndex =
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val lastModifiedIndex =
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                val mimeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)

                while (cursor.moveToNext()) {
                    val sourceUrl = cursor.getString(uriIndex).orEmpty()
                    val localUri = cursor.getString(localUriIndex).orEmpty()
                    val title = cursor.getString(titleIndex).orEmpty().ifBlank {
                        Uri.parse(sourceUrl).lastPathSegment ?: getString(R.string.downloads)
                    }
                    val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""

                    items.add(
                        DownloadItem(
                            id = cursor.getLong(idIndex),
                            title = title,
                            sourceUrl = sourceUrl,
                            localUri = localUri,
                            status = cursor.getInt(statusIndex),
                            totalBytes = cursor.getLong(totalSizeIndex),
                            downloadedBytes = cursor.getLong(downloadedIndex),
                            lastModified = cursor.getLong(lastModifiedIndex),
                            mimeType = mimeType
                        )
                    )
                }
            }
        } catch (error: Exception) {
            // A vendor DownloadManager can throw on a partially corrupted row; showing the
            // published files is still better than an empty screen.
            android.util.Log.w("DownloadsActivity", "DownloadManager query failed", error)
        }
        return items
    }

    /**
     * Files the video downloader published into `Download/ExtremisBrowser/`.
     *
     * MediaStore is queried on API 29+, where the relative path is a real column. Below that the
     * folder is listed directly, which is all that API level offers.
     */
    private fun queryPublishedDownloads(): List<DownloadItem> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryPublishedFromMediaStore()
        } else {
            queryPublishedFromDisk()
        }
    }

    private fun queryPublishedFromMediaStore(): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val arguments = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$PUBLISH_FOLDER/%")

        try {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arguments,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val size = cursor.getLong(sizeIndex)
                    items.add(
                        DownloadItem(
                            // Negative ids mark "not owned by DownloadManager", so delete and
                            // open take the MediaStore path instead of calling into it.
                            id = -id,
                            title = cursor.getString(nameIndex).orEmpty(),
                            sourceUrl = "",
                            localUri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                id
                            ).toString(),
                            status = DownloadManager.STATUS_SUCCESSFUL,
                            totalBytes = size,
                            downloadedBytes = size,
                            lastModified = cursor.getLong(modifiedIndex) * 1000L,
                            mimeType = cursor.getString(mimeIndex).orEmpty()
                        )
                    )
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("DownloadsActivity", "MediaStore download query failed", error)
        }
        return items
    }

    private fun queryPublishedFromDisk(): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLISH_FOLDER
        )
        val files = try {
            directory.listFiles()
        } catch (error: Exception) {
            android.util.Log.w("DownloadsActivity", "Could not list $directory", error)
            null
        } ?: return items

        files.filter { it.isFile && !it.name.startsWith(".") }.forEachIndexed { index, file ->
            items.add(
                DownloadItem(
                    id = -(index + 1L),
                    title = file.name,
                    sourceUrl = "",
                    localUri = file.absolutePath,
                    status = DownloadManager.STATUS_SUCCESSFUL,
                    totalBytes = file.length(),
                    downloadedBytes = file.length(),
                    lastModified = file.lastModified(),
                    mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase())
                        .orEmpty()
                )
            )
        }
        return items
    }

    // ------------------------------------------------------------------
    // Item actions
    // ------------------------------------------------------------------

    private fun showItemMenu(item: DownloadItem, anchor: View) {
        val options = arrayOf(
            getString(R.string.open),
            getString(R.string.copy_link),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDownload(item)
                    1 -> copyToClipboard(item.sourceUrl.ifBlank { item.localUri })
                    else -> removeDownload(item)
                }
            }
            .show()
    }

    private fun removeDownload(item: DownloadItem) {
        if (item.id > 0) {
            downloadManager.remove(item.id)
        } else {
            // Published playlist output: remove the MediaStore row, which also removes the file.
            try {
                contentResolver.delete(Uri.parse(item.localUri), null, null)
            } catch (error: Exception) {
                android.util.Log.w("DownloadsActivity", "Could not delete ${item.localUri}", error)
                Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show()
            }
        }
        loadDownloads()
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

    /**
     * Prefer the type the source reported. A `content://` URI has no extension, so the old
     * extension-only guess always fell through to the wildcard type and no player would accept it.
     */
    private fun resolveMimeType(item: DownloadItem): String {
        if (item.mimeType.isNotBlank()) return item.mimeType

        val candidate = item.title.ifBlank {
            if (item.localUri.isNotBlank()) item.localUri else item.sourceUrl
        }
        val extension = MimeTypeMap.getFileExtensionFromUrl(candidate).orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Must match the folder [com.extremis.browser.media.MediaDownloadService] publishes into. */
        const val PUBLISH_FOLDER = "ExtremisBrowser"
    }
}
