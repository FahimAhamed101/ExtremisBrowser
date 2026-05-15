package com.extremis.browser.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.databinding.ItemHistoryBinding
import com.extremis.browser.models.HistoryItem

class HistoryAdapter(
    private val onOpen: (HistoryItem) -> Unit,
    private val onDelete: (HistoryItem) -> Unit
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
            binding.titleView.text = item.title
            binding.urlView.text = item.url
            binding.timeView.text = DateUtils.getRelativeTimeSpanString(item.timestamp)
            binding.root.setOnClickListener { onOpen(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }
}
