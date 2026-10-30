package com.extremis.browser.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.databinding.ItemHistoryBinding
import com.extremis.browser.models.HistoryItem
import com.extremis.browser.utils.FaviconLoader

/**
 * Chrome-style history list. The second line shows the URL followed by how long ago the page
 * was visited, which is what Chrome does inside each day group.
 */
class HistoryAdapter(
    private val onOpen: (HistoryItem) -> Unit,
    private val onMenu: (HistoryItem, View) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<HistoryItem>()

    fun submitList(history: List<HistoryItem>) {
        items.clear()
        items.addAll(history)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            val title = item.title.trim().ifBlank {
                FaviconLoader.hostOf(item.url) ?: item.url
            }
            binding.titleView.text = title
            binding.subtitleView.text = "${item.url} \u00B7 " +
                DateUtils.getRelativeTimeSpanString(item.timestamp)
            FaviconLoader.load(item.url, binding.faviconView)
            binding.root.setOnClickListener { onOpen(item) }
            binding.moreButton.setOnClickListener { onMenu(item, binding.moreButton) }
        }
    }
}
