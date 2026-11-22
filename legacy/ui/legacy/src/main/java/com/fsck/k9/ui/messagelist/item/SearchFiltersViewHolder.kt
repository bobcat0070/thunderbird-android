package com.fsck.k9.ui.messagelist.item

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messagelist.MessageListViewItem
import com.fsck.k9.ui.messagelist.SearchFilters
import com.fsck.k9.ui.messagelist.SearchPeriod
import com.google.android.material.chip.Chip

/**
 * The chips at the top of a search's results that narrow it, and the one that saves it to the drawer.
 */
class SearchFiltersViewHolder(
    view: View,
    private val onFiltersChanged: (SearchFilters) -> Unit,
    private val onSaveClicked: () -> Unit,
    private val onRemoveClicked: () -> Unit,
) : MessageListViewHolder(view) {

    private val unread: Chip = view.findViewById(R.id.search_filter_unread)
    private val starred: Chip = view.findViewById(R.id.search_filter_starred)
    private val attachment: Chip = view.findViewById(R.id.search_filter_attachment)
    private val people: Chip = view.findViewById(R.id.search_filter_people)
    private val period: Chip = view.findViewById(R.id.search_filter_period)
    private val save: Chip = view.findViewById(R.id.search_filter_save)

    fun bind(item: MessageListViewItem.SearchFilters) {
        val filters = item.filters

        // Listeners are cleared first: the holder is recycled, and setting a chip's state would otherwise report it
        // as the reader's choice.
        bindChip(unread, filters.unread) { filters.copy(unread = it) }
        bindChip(starred, filters.starred) { filters.copy(starred = it) }
        bindChip(attachment, filters.hasAttachment) { filters.copy(hasAttachment = it) }
        bindChip(people, filters.fromPeople) { filters.copy(fromPeople = it) }

        period.setText(periodTitle(filters.period))
        val choosePeriod = View.OnClickListener { showPeriodMenu(filters) }
        period.setOnClickListener(choosePeriod)
        period.setOnCloseIconClickListener(choosePeriod)
        period.closeIconContentDescription = period.context.getString(R.string.search_filter_choose_period)

        save.setText(if (item.isSaved) R.string.search_remove_saved else R.string.search_save)
        save.setOnClickListener { if (item.isSaved) onRemoveClicked() else onSaveClicked() }
    }

    private fun bindChip(chip: Chip, isChecked: Boolean, changed: (Boolean) -> SearchFilters) {
        chip.setOnCheckedChangeListener(null)
        chip.isChecked = isChecked
        chip.setOnCheckedChangeListener { _, checked -> onFiltersChanged(changed(checked)) }
    }

    private fun showPeriodMenu(filters: SearchFilters) {
        PopupMenu(period.context, period).apply {
            SearchPeriod.entries.forEachIndexed { index, choice ->
                menu.add(0, index, index, periodTitle(choice))
            }
            setOnMenuItemClickListener { menuItem ->
                onFiltersChanged(filters.copy(period = SearchPeriod.entries[menuItem.itemId]))
                true
            }
        }.show()
    }

    private fun periodTitle(choice: SearchPeriod): Int = when (choice) {
        SearchPeriod.ANY_TIME -> R.string.search_filter_any_time
        SearchPeriod.LAST_7_DAYS -> R.string.search_filter_7_days
        SearchPeriod.LAST_30_DAYS -> R.string.search_filter_30_days
        SearchPeriod.LAST_YEAR -> R.string.search_filter_year
    }

    companion object {
        fun create(
            layoutInflater: LayoutInflater,
            parent: ViewGroup,
            onFiltersChanged: (SearchFilters) -> Unit,
            onSaveClicked: () -> Unit,
            onRemoveClicked: () -> Unit,
        ): SearchFiltersViewHolder {
            val view = layoutInflater.inflate(R.layout.message_list_item_search_filters, parent, false)
            return SearchFiltersViewHolder(view, onFiltersChanged, onSaveClicked, onRemoveClicked)
        }
    }
}
