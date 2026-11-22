package net.thunderbird.feature.newsletter.internal

import net.thunderbird.feature.newsletter.NewsletterSender

/**
 * How the newsletter senders are listed.
 */
internal enum class SenderOrder {
    /** The senders with the most newsletters still to look at first. */
    MOST_MAIL,

    /** The senders whose newsletters the reader opens least first: the ones to unsubscribe from. */
    LEAST_OPENED,
}

/**
 * How many newsletters a sender needs to have sent lately before not opening any of them means something.
 */
internal const val NEVER_OPENED_MIN_COUNT = 3

/**
 * Whether the reader opened none of this sender's recent newsletters, of which there were enough to tell.
 */
internal val NewsletterSender.isNeverOpened: Boolean
    get() = recentCount >= NEVER_OPENED_MIN_COUNT && recentOpenedCount == 0

internal fun List<NewsletterSender>.sortedFor(order: SenderOrder): List<NewsletterSender> = when (order) {
    SenderOrder.MOST_MAIL -> sortedWith(
        compareByDescending<NewsletterSender> { it.messageCount }.thenByDescending { it.latestAt },
    )

    // A sender with nothing recent says nothing about opening, so those come last; among equals, the one sending more.
    SenderOrder.LEAST_OPENED -> sortedWith(
        compareBy<NewsletterSender> { it.openedShare() ?: Double.MAX_VALUE }
            .thenByDescending { it.recentCount }
            .thenByDescending { it.messageCount },
    )
}

private fun NewsletterSender.openedShare(): Double? =
    if (recentCount == 0) null else recentOpenedCount.toDouble() / recentCount
