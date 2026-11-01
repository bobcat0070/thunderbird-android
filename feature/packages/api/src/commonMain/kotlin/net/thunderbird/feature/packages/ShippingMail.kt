package net.thunderbird.feature.packages

/**
 * The carriers whose tracking numbers the app recognises in mail.
 */
enum class ParcelCarrier {
    UPS,
    USPS,
    FEDEX,
    DHL,
}

/**
 * A tracking number found in a mail.
 *
 * @param trackingUrl the carrier's own tracking page for the number.
 */
data class ParcelNumber(
    val carrier: ParcelCarrier,
    val number: String,
    val trackingUrl: String,
)

/**
 * A stored mail that names one or more tracking numbers.
 *
 * @param key what the app needs to find the mail again; opaque to the feature.
 * @param date when the mail was sent.
 * @param senderName who sent it, as the reader would recognise them: the display name, or else the address.
 * @param text the mail's subject and readable text, cut to a length that holds what a shipping update says.
 */
data class ShippingMail(
    val key: String,
    val date: Long,
    val senderName: String?,
    val subject: String?,
    val parcels: List<ParcelNumber>,
    val text: String,
)

/**
 * Reads shipping mail from the reader's accounts and acts on it. Implemented by the app.
 */
interface ShippingMailSource {
    /**
     * The mail sent since [since] that names a tracking number, in every account, leaving out the trash and spam.
     * Only mail whose body is on the device can be read.
     */
    suspend fun shippingMails(since: Long): List<ShippingMail>

    /**
     * How many mails since [since] look like shipping mail but are known only by their envelope, as Microsoft 365
     * mail is until it is opened.
     */
    suspend fun notDownloadedCount(since: Long): Int

    /**
     * Downloads the newest [limit] of those mails so their tracking numbers can be read.
     *
     * @return how many were downloaded.
     */
    suspend fun downloadMails(since: Long, limit: Int, onProgress: (done: Int, total: Int) -> Unit): Int

    fun openMail(key: String)

    /**
     * Opens the app's search for [query] across every account, which also offers to search the servers.
     */
    fun searchAllMail(query: String)

    /**
     * Opens [url] through the link check.
     */
    fun openTrackingPage(url: String)
}
