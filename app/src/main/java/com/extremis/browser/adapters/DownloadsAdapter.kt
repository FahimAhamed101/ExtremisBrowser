package com.extremis.browser.adapters

import android.app.DownloadManager
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemDownloadBinding
import com.extremis.browser.models.DownloadItem

/**
 * Chrome-style downloads list. In-progress items show a progress bar; completed items show a
 * size and relative timestamp.
 */
class DownloadsAdapter(
    private val onOpen: (DownloadItem) -> Unit,
    private val onMenu: (DownloadItem, View) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.DownloadViewHolder>() {

    private val items = mutableListOf<DownloadItem>()

    fun submitList(downloads: List<DownloadItem>) {
        items.clear()
        items.addAll(downloads)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DownloadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DownloadViewHolder(
        private val binding: ItemDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            val context = binding.root.context
            binding.titleView.text = item.title

            val sizeText = if (item.totalBytes > 0L) {
                Formatter.formatFileSize(context, item.totalBytes)
            } else {
                context.getString(R.string.size_unknown)
            }
            val timeText = DateUtils.getRelativeTimeSpanString(item.lastModified).toString()
            binding.statusView.text = "${statusLabel(item.status)} \u00B7 $sizeText \u00B7 $timeText"

            val running = item.status == DownloadManager.STATUS_RUNNING
            binding.progressView.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                val total = item.totalBytes
                if (total > 0L) {
                    binding.progressView.isIndeterminate = false
                    binding.progressView.max = 100
                    binding.progressView.progress =
                        ((item.downloadedBytes * 100) / total).toInt().coerceIn(0, 100)
                } else {
                    binding.progressView.isIndeterminate = true
                }
            }

            val complete = item.status == DownloadManager.STATUS_SUCCESSFUL
            binding.openButton.visibility = if (complete) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onOpen(item) }
            binding.openButton.setOnClickListener { onOpen(item) }
            binding.iconView.setOnClickListener { onMenu(item, binding.iconView) }
        }

        private fun statusLabel(status: Int): String {
            val context = binding.root.context
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> context.getString(R.string.status_complete)
                DownloadManager.STATUS_RUNNING -> context.getString(R.string.status_running)
                DownloadManager.STATUS_PENDING -> context.getString(R.string.status_pending)
                DownloadManager.STATUS_PAUSED -> context.getString(R.string.status_paused)
                DownloadManager.STATUS_FAILED -> context.getString(R.string.status_failed)
                else -> context.getString(R.string.status_unknown)
            }
        }
    }
}
