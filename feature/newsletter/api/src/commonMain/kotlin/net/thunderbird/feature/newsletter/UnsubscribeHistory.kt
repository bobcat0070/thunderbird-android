package net.thunderbird.feature.newsletter

/**
 * When the reader asked to leave each sender's list, so mail that keeps coming afterwards can be pointed out.
 *
 * Kept by sender address: newsletters and a shop's receipts often share a domain, and only the address that was left
 * has promised to stop.
 */
interface UnsubscribeHistory {
    /**
     * Notes that the reader unsubscribed from [senderAddress] at [atMillis]: sent a one-click unsubscribe, or opened
     * the sender's page or unsubscribe address, which is as far as the app can follow.
     */
    fun recordUnsubscribe(senderAddress: String, atMillis: Long)

    /**
     * Forgets the unsubscribe from [senderAddress], for a reader who signed up again.
     */
    fun forget(senderAddress: String)
}

/**
 * Mail from a sender that arrived well after the reader unsubscribed from them.
 *
 * @param unsubscribedAt when the reader unsubscribed, in milliseconds since the epoch.
 */
data class StillMailing(
    val senderAddress: String,
    val unsubscribedAt: Long,
)

/**
 * Checks mail against the reader's unsubscribes.
 */
fun interface UnsubscribeFollowUp {
    /**
     * @param receivedAt when the message reached the mailbox, in milliseconds since the epoch.
     * @return the unsubscribe the message ignores, or `null` when it came in time or there was none.
     */
    fun check(senderAddress: String, receivedAt: Long): StillMailing?
}

/**
 * The unsubscribes as settings export carries them: when each sender was left, by address.
 */
interface UnsubscribeHistorySettings {
    fun exportUnsubscribes(): Map<String, Long>

    /**
     * Adds [unsubscribes] to what is kept; an address kept here already keeps its later date.
     */
    fun importUnsubscribes(unsubscribes: Map<String, Long>)
}
