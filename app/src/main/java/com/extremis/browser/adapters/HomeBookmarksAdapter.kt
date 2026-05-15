package com.extremis.browser.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemHomeBookmarkBinding
import com.extremis.browser.models.Bookmark
import java.util.Locale

class HomeBookmarksAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onAddBookmark: () -> Unit
) : RecyclerView.Adapter<HomeBookmarksAdapter.HomeBookmarkViewHolder>() {

    private val items = mutableListOf<Bookmark>()

    fun submitList(bookmarks: List<Bookmark>) {
        items.clear()
        items.addAll(bookmarks)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeBookmarkViewHolder {
        val binding = ItemHomeBookmarkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HomeBookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HomeBookmarkViewHolder, position: Int) {
        if (position == 0) {
            holder.bindAddTile()
        } else {
            holder.bindBookmark(items[position - 1])
        }
    }

    override fun getItemCount(): Int = items.size + 1

    inner class HomeBookmarkViewHolder(
        private val binding: ItemHomeBookmarkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bindBookmark(item: Bookmark) {
            val title = getDisplayTitle(item)
            binding.titleView.text = title
            binding.initialView.text = getInitial(title)
            binding.root.setOnClickListener { onOpen(item) }
        }

        fun bindAddTile() {
            binding.titleView.text = binding.root.context.getString(R.string.add_bookmark_shortcut)
            binding.initialView.text = "+"
            binding.root.setOnClickListener { onAddBookmark() }
        }
    }

    private fun getDisplayTitle(item: Bookmark): String {
        val cleanTitle = item.title.trim()
        if (cleanTitle.isNotEmpty() && !cleanTitle.equals(item.url, ignoreCase = true)) {
            return cleanTitle
        }

        val host = runCatching { Uri.parse(item.url).host }.getOrNull().orEmpty()
        if (host.isNotBlank()) return host.removePrefix("www.")

        return item.url
    }

    private fun getInitial(title: String): String {
        val initial = title.firstOrNull { it.isLetterOrDigit() } ?: '?'
        return initial.toString().uppercase(Locale.getDefault())
    }
}
