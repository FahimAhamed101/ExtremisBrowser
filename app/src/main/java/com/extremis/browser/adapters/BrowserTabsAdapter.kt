package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.R
import com.extremis.browser.databinding.ItemBrowserTabBinding
import com.extremis.browser.models.BrowserTab

class BrowserTabsAdapter(
    private val onOpen: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<BrowserTabsAdapter.TabViewHolder>() {

    private val tabs = mutableListOf<Pair<Int, BrowserTab>>()
    private var currentIndex = 0

    fun submitTabs(items: List<BrowserTab>, selectedIndex: Int, query: String = "") {
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

        fun bind(tab: BrowserTab, originalIndex: Int, selected: Boolean) {
            binding.cardRoot.setBackgroundResource(
                when {
                    tab.incognito && selected -> R.drawable.incognito_tab_card_selected_bg
                    tab.incognito -> R.drawable.incognito_tab_card_bg
                    selected -> R.drawable.chrome_tab_card_selected_bg
                    else -> R.drawable.chrome_tab_card_bg
                }
            )
            binding.titleView.text = tab.title.ifBlank {
                if (tab.incognito) {
                    binding.root.context.getString(R.string.new_incognito_tab)
                } else {
                    binding.root.context.getString(R.string.new_tab)
                }
            }
            binding.urlView.text = when (tab.url) {
                BrowserTab.NEW_TAB_URL -> binding.root.context.getString(R.string.search_or_type_url)
                else -> tab.url
            }
            binding.incognitoIcon.visibility = if (tab.incognito) View.VISIBLE else View.GONE
            binding.previewLogo.text = if (tab.incognito) {
                "Private"
            } else {
                binding.root.context.getString(R.string.app_name)
            }
            binding.previewSurface.setBackgroundResource(
                if (tab.incognito) R.drawable.incognito_tab_card_bg else R.drawable.chrome_search_bg
            )
            binding.root.setOnClickListener { onOpen(originalIndex) }
            binding.closeButton.setOnClickListener { onClose(originalIndex) }
        }
    }
}
