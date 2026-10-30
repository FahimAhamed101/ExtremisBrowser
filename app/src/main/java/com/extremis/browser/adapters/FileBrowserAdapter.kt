package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemFileBinding
import com.extremis.browser.models.FileEntry
import com.extremis.browser.utils.FileUtils
import java.text.DateFormat
import java.util.Date

/**
 * File/folder rows for the storage browser, ported from KoiBrowser's `FileBrowserAdapter` +
 * `StorageListActivity`. Directories sort first, then files, both alphabetically.
 */
class FileBrowserAdapter(
    private val onOpen: (FileEntry) -> Unit,
    private val onMenu: (FileEntry, View) -> Unit
) : RecyclerView.Adapter<FileBrowserAdapter.FileViewHolder>() {

    private val entries = mutableListOf<FileEntry>()

    fun submit(items: List<FileEntry>) {
        entries.clear()
        entries.addAll(
            items.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FileEntry) {
            val context = binding.root.context
            binding.fileName.text = entry.name

            if (entry.isDirectory) {
                binding.fileIcon.setImageResource(R.drawable.ic_folder_24)
                binding.fileSummary.text = context.resources.getQuantityString(
                    R.plurals.file_count, entry.itemCount, entry.itemCount
                )
                binding.rowChevron.visibility = View.VISIBLE
            } else {
                binding.fileIcon.setImageResource(R.drawable.ic_globe_24)
                val modified = DateFormat.getDateInstance(DateFormat.SHORT)
                    .format(Date(entry.lastModified))
                binding.fileSummary.text = "${FileUtils.formatSize(entry.sizeBytes)} · $modified"
                binding.rowChevron.visibility = View.GONE
            }

            val iconTint = ContextCompat.getColor(context, R.color.chrome_icon)
            binding.fileIcon.setColorFilter(iconTint)
            binding.rowChevron.setColorFilter(
                ContextCompat.getColor(context, R.color.chrome_muted)
            )

            binding.root.setOnClickListener { onOpen(entry) }
            binding.root.setOnLongClickListener {
                onMenu(entry, binding.root)
                true
            }
        }
    }
}
