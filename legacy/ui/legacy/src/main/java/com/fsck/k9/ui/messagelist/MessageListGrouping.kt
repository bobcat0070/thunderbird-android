package com.fsck.k9.ui.messagelist

import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * The classes that are collapsed into a bundle, in the order their bundles are preferred when both fall at
 * the same place in the list.
 *
 * [MessageClass.HUMAN] and [MessageClass.UNKNOWN] are deliberately absent. Unknown mail stays in the list
 * because the classifier could not say what it was, and hiding it behind a bundle would turn "we don't know"
 * into "this is bulk" — the mistake that would cost the user a real message.
 */
private val BUNDLED_CLASSES = listOf(MessageClass.NOTIFICATION, MessageClass.NEWSLETTER)

/**
 * How many senders a collapsed bundle names.
 */
private const val BUNDLE_PREVIEW_SENDERS = 4

/**
 * Collapses bulk mail into per-class bundles while leaving correspondence in place.
 *
 * A bundle sits where its newest message would have been, so the list still reads in date order and a burst
 * of notifications does not push a person's mail off the screen.
 *
 * @param expandedClasses bundles the user has opened; their messages follow the bundle row.
 */
fun List<MessageListItem>.groupByClassification(
    expandedClasses: Set<MessageClass>,
): List<MessageListViewItem> {
    val bundles = BUNDLED_CLASSES.associateWith { messageClass -> filter { it.classification == messageClass } }
        .filterValues { it.isNotEmpty() }

    if (bundles.isEmpty()) return map { MessageListViewItem.Message(it) }

    // A bundle is emitted in place of its newest member, so the row it replaces is the one that decides
    // where the whole group sits.
    val bundleAnchors = bundles.mapValues { (_, messages) -> messages.first().uniqueId }

    return buildList {
        for (item in this@groupByClassification) {
            val bundleClass = item.classification.takeIf { it in bundles }

            when {
                bundleClass == null -> add(MessageListViewItem.Message(item))

                bundleAnchors[bundleClass] == item.uniqueId -> {
                    addBundle(bundleClass, bundles.getValue(bundleClass), bundleClass in expandedClasses)
                }
            }
        }
    }
}

private fun MutableList<MessageListViewItem>.addBundle(
    messageClass: MessageClass,
    messages: List<MessageListItem>,
    isExpanded: Boolean,
) {
    add(
        MessageListViewItem.Bundle(
            messageClass = messageClass,
            messageCount = messages.size,
            unreadCount = messages.count { !it.isRead },
            senderNames = messages.map { it.displayName.toString() }
                .distinct()
                .take(BUNDLE_PREVIEW_SENDERS),
            isExpanded = isExpanded,
        ),
    )

    if (isExpanded) {
        addAll(messages.map { MessageListViewItem.Message(it) })
    }
}
