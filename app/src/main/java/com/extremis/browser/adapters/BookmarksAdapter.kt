package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.databinding.ItemBookmarkBinding
import com.extremis.browser.models.Bookmark
import com.extremis.browser.utils.FaviconLoader

/**
 * Chrome-style bookmark list: favicon, page title, URL, and a per-row overflow button.
 */
class BookmarksAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onMenu: (Bookmark, View) -> Unit
) : RecyclerView.Adapter<BookmarksAdapter.BookmarkViewHolder>() {

    private val items = mutableListOf<Bookmark>()

    fun submitList(bookmarks: List<Bookmark>) {
        items.clear()
        items.addAll(bookmarks)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class BookmarkViewHolder(
        private val binding: ItemBookmarkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Bookmark) {
            val title = item.title.trim().ifBlank {
                FaviconLoader.hostOf(item.url) ?: item.url
            }
            binding.titleView.text = title
            binding.urlView.text = item.url
            FaviconLoader.load(item.url, binding.faviconView)
            binding.root.setOnClickListener { onOpen(item) }
            binding.moreButton.setOnClickListener { onMenu(item, binding.moreButton) }
        }
    }
}
