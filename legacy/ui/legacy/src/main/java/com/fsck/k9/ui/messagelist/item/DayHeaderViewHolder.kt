package com.fsck.k9.ui.messagelist.item

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messagelist.MessageListViewItem
import com.fsck.k9.ui.messagelist.RelativeDay
import com.google.android.material.textview.MaterialTextView

/**
 * Names the day the messages below it arrived.
 *
 * The two most recent days are named rather than dated, because "Today" is what someone is actually looking
 * for when they scan a mailbox, and a date makes them work it out.
 */
class DayHeaderViewHolder(view: View) : MessageListViewHolder(view) {
    private val textView: MaterialTextView = view.findViewById(R.id.day_header)

    fun bind(dayHeader: MessageListViewItem.DayHeader) {
        textView.text = when (dayHeader.relativeDay) {
            RelativeDay.TODAY -> textView.context.getString(R.string.message_list_day_today)
            RelativeDay.YESTERDAY -> textView.context.getString(R.string.message_list_day_yesterday)
            RelativeDay.EARLIER -> formatDate(dayHeader.timestamp)
        }
    }

    /**
     * The year is only shown once the date is no longer in this one, which is the point at which leaving it
     * out becomes ambiguous.
     */
    private fun formatDate(timestamp: Long): CharSequence {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL

        return DateUtils.formatDateTime(textView.context, timestamp, flags)
    }

    companion object {
        fun create(layoutInflater: LayoutInflater, parent: ViewGroup): DayHeaderViewHolder {
            val view = layoutInflater.inflate(R.layout.message_list_item_day_header, parent, false)

            return DayHeaderViewHolder(view)
        }
    }
}
