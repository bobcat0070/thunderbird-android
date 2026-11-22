package net.thunderbird.feature.impersonation

/**
 * Replies to a message would go to an address at another domain than the one the message came from.
 *
 * Senders set a Reply-To for ordinary reasons, but it is also how a forged or hijacked invoice gets the answer -
 * with the bank details or the payment confirmation - to someone other than the sender it names.
 *
 * @param replyToAddress where replies would go.
 * @param senderAddress who the message says it is from.
 * @param senderDomain the domain the sender registered, which the reply address is not on.
 */
public data class ReplyToMismatch(
    val replyToAddress: String,
    val senderAddress: String,
    val senderDomain: String,
)

/**
 * Checks where replies to a message would go. Mailing lists are left to the caller: a list sets its own address as
 * Reply-To by design, so a message from one is not checked at all.
 */
public fun interface ReplyToChecker {
    /**
     * @return the first Reply-To address that is neither on the sender's domain, nor the reader's own, nor someone
     *   they have written to or have as a contact; `null` when there is none.
     */
    public fun check(senderAddress: String?, replyToAddresses: List<String>): ReplyToMismatch?
}

/**
 * Tells whether a message is the first the device has from its sender.
 *
 * Calls may read stored mail, so they belong off the main thread.
 */
public fun interface FirstTimeSenderChecker {
    /**
     * @return `true` when the reader has never written to [senderAddress] or has it as a contact, and no mail from
     *   it is stored other than the message being read.
     */
    public fun isFirstMail(senderAddress: String): Boolean
}

/**
 * Counts the mail the device holds from an address, in every account and folder. Bound by the app.
 */
public fun interface StoredMailCounter {
    /**
     * @return how many stored messages are from [senderAddress], counting no further than [atMost].
     */
    public fun countMailFrom(senderAddress: String, atMost: Int): Int
}
