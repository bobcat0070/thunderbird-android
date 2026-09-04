package com.fsck.k9.ui.messagelist.item

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messagelist.MessageListViewItem
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

/**
 * The control at the very top of the list for turning categories on and off.
 *
 * In the list rather than behind a menu because it is a mode the reader switches between, not a preference
 * they set once: triaging wants categories, hunting for one message wants a plain date-ordered list.
 */
class GroupingToggleViewHolder(
    view: View,
    private val onToggled: (Boolean) -> Unit,
) : MessageListViewHolder(view) {

    private val label: MaterialTextView = view.findViewById(R.id.grouping_toggle_label)
    private val switch: MaterialSwitch = view.findViewById(R.id.grouping_toggle_switch)

    fun bind(item: MessageListViewItem.GroupingToggle) {
        label.setText(
            if (item.isEnabled) R.string.message_list_grouping_on else R.string.message_list_grouping_off,
        )

        // Cleared first: the holder is recycled, and setting the state would otherwise fire the listener as
        // though the user had flipped it.
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = item.isEnabled
        switch.setOnCheckedChangeListener { _, isChecked -> onToggled(isChecked) }
    }

    companion object {
        fun create(
            layoutInflater: LayoutInflater,
            parent: ViewGroup,
            onToggled: (Boolean) -> Unit,
        ): GroupingToggleViewHolder {
            val view = layoutInflater.inflate(R.layout.message_list_item_grouping_toggle, parent, false)

            return GroupingToggleViewHolder(view, onToggled)
        }
    }
}
