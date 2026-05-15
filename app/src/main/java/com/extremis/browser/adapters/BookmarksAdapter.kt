package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.databinding.ItemBookmarkBinding
import com.extremis.browser.models.Bookmark

class BookmarksAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit
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
            binding.titleView.text = item.title
            binding.urlView.text = item.url
            binding.root.setOnClickListener { onOpen(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }
}
