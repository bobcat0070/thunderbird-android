package net.thunderbird.feature.trips

/**
 * The senders whose mail Trips reads for bookings, and the single mails the reader marked as trip mail. Kept by the
 * Trips feature; the message view adds to it when the reader marks a mail.
 */
interface TravelSenders {
    /**
     * The domain mail from [address] is listed under: the domain its sender registered, as `flyfrontier.com` for
     * `flights@t.flyfrontier.com`.
     *
     * @return the domain, or `null` when [address] has none, or is at a mail provider such as gmail.com, where
     *   listing the domain would take in everyone's mail.
     */
    fun domainOf(address: String): String?

    fun isListed(domain: String): Boolean

    fun setListed(domain: String, isListed: Boolean)

    fun isMarked(messageId: String): Boolean

    fun setMarked(messageId: String, isMarked: Boolean)
}
