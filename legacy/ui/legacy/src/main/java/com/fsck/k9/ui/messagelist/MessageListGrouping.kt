package com.fsck.k9.ui.messagelist

import java.util.Calendar
import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * The classes that are collapsed into a category row, in the order those rows appear.
 *
 * [MessageClass.HUMAN] and [MessageClass.UNKNOWN] are deliberately absent. Unknown mail stays in the list
 * because the classifier could not say what it was, and hiding it behind a category would turn "we don't
 * know" into "this is bulk" — the mistake that would cost the user a real message.
 */
private val BUNDLED_CLASSES = listOf(MessageClass.NOTIFICATION, MessageClass.NEWSLETTER)

/**
 * How many senders a category row names.
 */
private const val BUNDLE_PREVIEW_SENDERS = 4

/**
 * Builds the rows of the message list.
 *
 * @param isGroupingEnabled when off, every message stays in one date-ordered list. Someone hunting for a
 *   specific message is served by a plain list; someone triaging is served by categories, and the toggle at
 *   the top is how they say which they are doing.
 * @param now the clock used to name the day groups, so "Today" means today.
 */
fun List<MessageListItem>.toViewItems(
    isGroupingEnabled: Boolean,
    showDayHeaders: Boolean,
    now: Long = System.currentTimeMillis(),
): List<MessageListViewItem> {
    val bundles = if (isGroupingEnabled) bundlesFor(this) else emptyList()
    val bundledClasses = bundles.map { (messageClass, _) -> messageClass }.toSet()
    val remaining = filterNot { it.classification in bundledClasses }

    return buildList {
        add(MessageListViewItem.GroupingToggle(isGroupingEnabled))

        for ((messageClass, messages) in bundles) {
            add(bundleFor(messageClass, messages))
        }

        if (showDayHeaders) {
            addAll(remaining.withDayHeaders(now))
        } else {
            addAll(remaining.map { MessageListViewItem.Message(it) })
        }
    }
}

private fun bundlesFor(items: List<MessageListItem>): List<Pair<MessageClass, List<MessageListItem>>> =
    BUNDLED_CLASSES
        .map { messageClass -> messageClass to items.filter { it.classification == messageClass } }
        .filter { (_, messages) -> messages.isNotEmpty() }

private fun bundleFor(messageClass: MessageClass, messages: List<MessageListItem>) = MessageListViewItem.Bundle(
    messageClass = messageClass,
    messageCount = messages.size,
    unreadCount = messages.count { !it.isRead },
    senderNames = messages.map { it.displayName.toString() }.distinct().take(BUNDLE_PREVIEW_SENDERS),
)

/**
 * Inserts a header whenever the day changes.
 *
 * The list is already in date order, so this only has to notice the boundaries rather than sort anything. A
 * header is emitted for the first message too, so the top of the list says what day it is looking at.
 */
private fun List<MessageListItem>.withDayHeaders(now: Long): List<MessageListViewItem> {
    var previousDay: Int? = null

    return buildList {
        for (item in this@withDayHeaders) {
            val day = item.messageDate.toDayOrdinal()

            if (day != previousDay) {
                add(MessageListViewItem.DayHeader(item.messageDate, day, dayRelativeTo(item.messageDate, now)))
                previousDay = day
            }

            add(MessageListViewItem.Message(item))
        }
    }
}

/**
 * How a day relates to today, which is what decides whether it is named or dated.
 */
enum class RelativeDay {
    TODAY,
    YESTERDAY,
    EARLIER,
}

private fun dayRelativeTo(timestamp: Long, now: Long): RelativeDay {
    val difference = now.toDayOrdinal() - timestamp.toDayOrdinal()

    return when (difference) {
        0 -> RelativeDay.TODAY
        1 -> RelativeDay.YESTERDAY
        else -> RelativeDay.EARLIER
    }
}

/**
 * A day number in the device's own time zone, so a boundary falls at the reader's midnight rather than UTC's.
 */
private fun Long.toDayOrdinal(): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = this@toDayOrdinal }

    return calendar.get(Calendar.YEAR) * DAYS_PER_YEAR_BUCKET + calendar.get(Calendar.DAY_OF_YEAR)
}

/**
 * Larger than any day-of-year, so year and day pack into one comparable number without colliding.
 */
private const val DAYS_PER_YEAR_BUCKET = 1000
