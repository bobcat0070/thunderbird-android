package net.thunderbird.feature.newsletter

/**
 * Someone sending the reader newsletters.
 *
 * @param messageCount how many of their newsletters are still where the reader would see them: not archived, in
 *   the trash or in spam.
 * @param canUnsubscribe whether their newest newsletter says how to leave the list.
 */
data class NewsletterSender(
    val address: String,
    val name: String?,
    val messageCount: Int,
    val latestAt: Long,
    val canUnsubscribe: Boolean,
)

/**
 * Reads who sends the reader newsletters, across every account.
 */
fun interface NewsletterSenderRepository {
    /**
     * @return the senders, most newsletters first.
     */
    suspend fun senders(): List<NewsletterSender>
}

/**
 * What happened to a request to leave a sender's list.
 */
enum class UnsubscribeOutcome {
    /** The app sent a one-click unsubscribe and the sender accepted it. */
    SENT,

    /** The sender's own page or unsubscribe address was opened for the reader to finish there. */
    OPENED_ELSEWHERE,

    /** The sender says nothing about how to leave. */
    UNAVAILABLE,
}

/**
 * What can be done about a sender, across every account.
 */
interface NewsletterActions {
    /**
     * Opens the list of everything [address] has sent.
     */
    fun showAllMail(address: String)

    /**
     * Leaves [address]'s list: by one-click unsubscribe when the sender offers it, otherwise - or when the sender
     * refuses it - by opening their page or address.
     */
    suspend fun unsubscribe(address: String): UnsubscribeOutcome

    /**
     * @return how many messages were archived.
     */
    suspend fun archiveAll(address: String): Int

    /**
     * Moves everything from [address] to the trash, leaving what is already there.
     *
     * @return how many messages were moved.
     */
    suspend fun deleteAll(address: String): Int
}
