package com.fsck.k9.ui.messagelist

import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * The classes that are collapsed into a bundle, in the order their rows appear.
 *
 * [MessageClass.HUMAN] and [MessageClass.UNKNOWN] are deliberately absent. Unknown mail stays in the list
 * because the classifier could not say what it was, and hiding it behind a bundle would turn "we don't know"
 * into "this is bulk" — the mistake that would cost the user a real message.
 */
private val BUNDLED_CLASSES = listOf(MessageClass.NOTIFICATION, MessageClass.NEWSLETTER)

/**
 * How many senders a bundle row names.
 */
private const val BUNDLE_PREVIEW_SENDERS = 4

/**
 * Lifts bulk mail out of the list and into one row per class at the top.
 *
 * Pinned rather than placed in date order because a bundle is a destination, not an event: it stands for
 * every message of its class in the folder, not only the newest one, so there is no single point in the
 * timeline that honestly belongs to it. Keeping the rows in one fixed place also means the entry point to a
 * category does not move as mail arrives.
 */
fun List<MessageListItem>.groupByClassification(): List<MessageListViewItem> {
    val bundles = BUNDLED_CLASSES
        .map { messageClass -> messageClass to filter { it.classification == messageClass } }
        .filter { (_, messages) -> messages.isNotEmpty() }

    if (bundles.isEmpty()) return map { MessageListViewItem.Message(it) }

    val bundledClasses = bundles.map { (messageClass, _) -> messageClass }.toSet()

    return buildList {
        for ((messageClass, messages) in bundles) {
            add(
                MessageListViewItem.Bundle(
                    messageClass = messageClass,
                    messageCount = messages.size,
                    unreadCount = messages.count { !it.isRead },
                    senderNames = messages.map { it.displayName.toString() }
                        .distinct()
                        .take(BUNDLE_PREVIEW_SENDERS),
                ),
            )
        }

        for (item in this@groupByClassification) {
            if (item.classification !in bundledClasses) {
                add(MessageListViewItem.Message(item))
            }
        }
    }
}
