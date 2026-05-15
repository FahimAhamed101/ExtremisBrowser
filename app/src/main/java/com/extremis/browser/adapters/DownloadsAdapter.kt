package com.extremis.browser.adapters

import android.app.DownloadManager
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemDownloadBinding
import com.extremis.browser.models.DownloadItem

class DownloadsAdapter(
    private val onOpen: (DownloadItem) -> Unit
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
            binding.titleView.text = item.title
            binding.statusView.text = statusLabel(item.status)
            binding.pathView.text = item.localUri.ifBlank { item.sourceUrl }

            val sizeText = if (item.totalBytes > 0L) {
                Formatter.formatFileSize(binding.root.context, item.totalBytes)
            } else {
                binding.root.context.getString(R.string.size_unknown)
            }
            binding.metaView.text =
                "$sizeText \u2022 ${DateUtils.getRelativeTimeSpanString(item.lastModified)}"

            binding.root.setOnClickListener { onOpen(item) }
            binding.openButton.setOnClickListener { onOpen(item) }
        }

        private fun statusLabel(status: Int): String {
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> binding.root.context.getString(R.string.status_complete)
                DownloadManager.STATUS_RUNNING -> binding.root.context.getString(R.string.status_running)
                DownloadManager.STATUS_PENDING -> binding.root.context.getString(R.string.status_pending)
                DownloadManager.STATUS_PAUSED -> binding.root.context.getString(R.string.status_paused)
                DownloadManager.STATUS_FAILED -> binding.root.context.getString(R.string.status_failed)
                else -> binding.root.context.getString(R.string.status_unknown)
            }
        }
    }
}
