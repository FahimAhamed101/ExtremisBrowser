package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemBrowserTabBinding
import com.extremis.browser.tabs.TabSession
import com.extremis.browser.utils.FaviconLoader

/**
 * Chrome-style tab switcher grid. Each card shows the tab title with its favicon, a close
 * button, and a preview surface. Incognito tabs use the dark Chrome palette.
 */
class BrowserTabsAdapter(
    private val onOpen: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<BrowserTabsAdapter.TabViewHolder>() {

    private val tabs = mutableListOf<Pair<Int, TabSession>>()
    private var currentIndex = 0

    fun submitTabs(items: List<TabSession>, selectedIndex: Int, query: String = "") {
        val cleanQuery = query.trim()
        tabs.clear()
        tabs.addAll(
            items.mapIndexed { index, tab -> index to tab }
                .filter { (_, tab) ->
                    cleanQuery.isBlank() ||
                        tab.title.contains(cleanQuery, ignoreCase = true) ||
                        tab.url.contains(cleanQuery, ignoreCase = true)
                }
        )
        currentIndex = selectedIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemBrowserTabBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val (originalIndex, tab) = tabs[position]
        holder.bind(tab, originalIndex, originalIndex == currentIndex)
    }

    override fun getItemCount(): Int = tabs.size

    inner class TabViewHolder(
        private val binding: ItemBrowserTabBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: TabSession, originalIndex: Int, selected: Boolean) {
            val context = binding.root.context
            val incognito = tab.incognito

            binding.cardRoot.setBackgroundResource(
                when {
                    incognito && selected -> R.drawable.incognito_tab_card_selected_bg
                    incognito -> R.drawable.incognito_tab_card_bg
                    selected -> R.drawable.chrome_tab_card_selected_bg
                    else -> R.drawable.chrome_tab_card_bg
                }
            )

            binding.titleView.text = tab.title.ifBlank {
                context.getString(
                    if (incognito) R.string.new_incognito_tab else R.string.new_tab
                )
            }
            binding.urlView.text = when (tab.url) {
                TabSession.NEW_TAB_URL -> context.getString(R.string.search_or_type_url)
                else -> tab.url
            }

            val titleColor = ContextCompat.getColor(
                context,
                if (incognito) R.color.incognito_text else R.color.chrome_text
            )
            val mutedColor = ContextCompat.getColor(
                context,
                if (incognito) R.color.incognito_muted else R.color.chrome_muted
            )
            binding.titleView.setTextColor(titleColor)
            binding.urlView.setTextColor(mutedColor)

            val iconTint = ContextCompat.getColor(
                context,
                if (incognito) R.color.incognito_muted else R.color.chrome_icon
            )
            binding.closeButton.setColorFilter(iconTint)
            binding.previewIcon.setColorFilter(iconTint)

            binding.previewSurface.setBackgroundResource(
                if (incognito) R.drawable.incognito_tab_card_bg else R.drawable.chrome_thumb_bg
            )

            // Chrome hides the preview favicon on the New Tab Page.
            binding.previewIcon.visibility =
                if (tab.isNewTab) View.GONE else View.VISIBLE

            if (incognito) {
                // Never hit the network from an incognito tab.
                binding.faviconView.setImageResource(R.drawable.ic_incognito_24)
                binding.faviconView.setColorFilter(iconTint)
            } else {
                binding.faviconView.clearColorFilter()
                FaviconLoader.load(tab.url, binding.faviconView)
            }

            binding.root.setOnClickListener { onOpen(originalIndex) }
            binding.closeButton.setOnClickListener { onClose(originalIndex) }
        }
    }
}
