package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemVideoDownloadBinding
import com.extremis.browser.media.MediaItem
import com.extremis.browser.media.MediaKind

/**
 * Lists the streams the scanner found on the current page.
 *
 * DASH manifests are shown but not offered for download: joining separate video and audio tracks
 * needs a muxer, and shipping a broken download would be worse than saying so up front.
 */
class MediaListAdapter(
    private val onDownload: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaListAdapter.MediaViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submit(list: List<MediaItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemVideoDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class MediaViewHolder(
        private val binding: ItemVideoDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            val context = binding.root.context

            binding.mediaIcon.setImageResource(
                if (item.kind == MediaKind.DIRECT && item.mimeType.startsWith("audio")) {
                    R.drawable.ic_download_24
                } else {
                    R.drawable.ic_video_download_24
                }
            )

            binding.mediaTitle.text = item.title.ifBlank { item.host }

            val host = item.host
            binding.mediaSummary.text = if (host.isBlank()) {
                item.summary
            } else {
                "${item.summary} · $host"
            }

            if (item.kind == MediaKind.DASH) {
                binding.mediaAction.setText(R.string.download_unsupported)
                binding.mediaAction.alpha = 0.5f
                binding.mediaAction.isEnabled = false
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            } else {
                binding.mediaAction.setText(R.string.download)
                binding.mediaAction.alpha = 1f
                binding.mediaAction.isEnabled = true
                binding.root.isClickable = true
                binding.root.setOnClickListener { onDownload(item) }
                binding.mediaAction.setOnClickListener { onDownload(item) }
            }

            binding.mediaAction.contentDescription =
                "${context.getString(R.string.download)} ${item.summary}"
        }
    }
}
