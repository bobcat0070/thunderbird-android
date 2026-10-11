package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.core.android.account.SortType
import net.thunderbird.feature.search.legacy.LocalMessageSearch

data class MessageListConfig(
    val search: LocalMessageSearch,
    val showingThreadedList: Boolean,
    val sortType: SortType,
    val sortAscending: Boolean,
    val sortDateAscending: Boolean,
    val activeMessage: MessageReference?,
    val sortOverrides: Map<MessageReference, MessageSortOverride>,
)

/**
 * Whether this lists the same messages as [other]: only the message being read differs, with the read and starred
 * states remembered while it was read. Opening a message, and going back to the list from it, changes nothing else.
 */
internal fun MessageListConfig.isSameListAs(other: MessageListConfig?): Boolean {
    return other != null &&
        copy(activeMessage = null, sortOverrides = emptyMap()) ==
        other.copy(activeMessage = null, sortOverrides = emptyMap())
}

data class MessageSortOverride(
    val isRead: Boolean,
    val isStarred: Boolean,
)
