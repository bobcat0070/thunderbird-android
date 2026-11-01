package net.thunderbird.feature.trips

/**
 * A stored mail that may confirm a booking, as much of it as can be read without its body.
 *
 * @param key what the app needs to find the mail again; opaque to the feature.
 * @param date when the mail was sent.
 * @param isDownloaded whether the mail's body is on the device; one that is not cannot be read for bookings.
 * @param isMarked whether the reader marked this mail as trip mail.
 */
data class TravelMailHeader(
    val key: String,
    val date: Long,
    val senderName: String?,
    val senderAddress: String?,
    val subject: String?,
    val isDownloaded: Boolean,
    val isMarked: Boolean = false,
)

/**
 * A mail's body: its HTML part, where booking markup lives, and its text.
 */
data class TravelMailContent(
    val html: String?,
    val text: String?,
)

/**
 * Reads booking mail from the reader's accounts. Implemented by the app.
 */
interface TravelMailSource {
    /**
     * The mail sent since [since] that may confirm a booking, in every account, leaving out the trash and spam: mail
     * whose subject suggests a booking - a confirmation, an itinerary, a reservation - mail from [senderDomains]
     * whose subject suggests anything about a trip, and the mails whose Message-ID is in [markedMessageIds], which
     * come back marked.
     *
     * @param senderDomains domains such as `flyfrontier.com`, each also standing for its subdomains.
     */
    suspend fun candidates(
        since: Long,
        senderDomains: Set<String>,
        markedMessageIds: Set<String>,
    ): List<TravelMailHeader>

    /**
     * @return the mail's body, or `null` when it is not on the device or the mail is gone.
     */
    suspend fun content(key: String): TravelMailContent?

    /**
     * Downloads the bodies of the mails [keys] names, as Microsoft 365 mail is stored only by its envelope until it
     * is opened.
     *
     * @return how many were downloaded.
     */
    suspend fun download(keys: List<String>, onProgress: (done: Int, total: Int) -> Unit): Int

    fun openMail(key: String)
}
