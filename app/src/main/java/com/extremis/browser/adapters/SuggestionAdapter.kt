package com.extremis.browser.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.extremis.browser.databinding.ItemSuggestionBinding

/**
 * Autocomplete rows under the omnibox.
 *
 * KoiBrowser's `adapter/SuggestionsAdapter` used a two-line layout that mixed history entries with
 * live suggestions and a "delete" affordance. We keep the single-line suggestion list, which is
 * what the omnibox dropdown actually needs.
 */
class SuggestionAdapter(
    private val onSuggestionClick: (String) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.SuggestionViewHolder>() {

    private val items = mutableListOf<String>()
    private var query: String = ""

    fun submit(suggestions: List<String>, forQuery: String) {
        items.clear()
        items.addAll(suggestions)
        query = forQuery
        notifyDataSetChanged()
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemSuggestionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        holder.bind(items[position], query)
    }

    override fun getItemCount(): Int = items.size

    inner class SuggestionViewHolder(
        private val binding: ItemSuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(suggestion: String, forQuery: String) {
            binding.suggestionText.text = suggestion
            binding.root.setOnClickListener { onSuggestionClick(suggestion) }
            binding.suggestionAction.setOnClickListener { onSuggestionClick(suggestion) }

            // Chrome bolds the part of the suggestion the user has already typed.
            val text = binding.suggestionText.text.toString()
            if (forQuery.isNotEmpty() && text.startsWith(forQuery, ignoreCase = true)) {
                val span = android.text.SpannableString(text)
                span.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0,
                    forQuery.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                binding.suggestionText.text = span
            }
        }
    }
}
