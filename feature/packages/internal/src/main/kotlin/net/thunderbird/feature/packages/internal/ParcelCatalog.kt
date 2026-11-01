package net.thunderbird.feature.packages.internal

import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ShippingMail

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/**
 * How long a delivered parcel stays in the list, so "it says delivered, where is it?" can still be looked up.
 */
internal const val DELIVERED_SHOWN_DAYS = 7

/**
 * How long a parcel no mail has mentioned stays on the way. Shops rarely send a "delivered" mail; after this long
 * the parcel has almost certainly arrived.
 */
internal const val QUIET_DAYS = 21

/**
 * A mail that mentions a parcel.
 */
data class ParcelMail(
    val key: String,
    val date: Long,
    val senderName: String?,
    val subject: String?,
)

/**
 * A parcel, as all the mail that mentions its tracking number describes it.
 *
 * @property shop who sent the first mail about it, usually the shop.
 * @property title the subject of the first mail about it.
 * @property mailStatus the status the newest mail that reports one gives.
 * @property mails every mail that mentions the parcel, newest first.
 * @property isMarkedDelivered whether the reader said it has arrived.
 */
data class Parcel(
    val number: String,
    val carrier: ParcelCarrier,
    val trackingUrl: String,
    val shop: String?,
    val title: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val mailStatus: ParcelStatus,
    val mails: List<ParcelMail>,
    val isMarkedDelivered: Boolean = false,
)

/**
 * The reader's own word on parcels, kept between visits.
 */
interface ParcelMarks {
    fun markedDelivered(): Set<String>
    fun removed(): Set<String>
    fun setMarkedDelivered(number: String, isDelivered: Boolean)
    fun remove(number: String)
}

/**
 * Gathers [mails] into parcels, one per tracking number, newest news first. Parcels the reader removed are left out,
 * as are those delivered more than [DELIVERED_SHOWN_DAYS] ago and those no mail has mentioned for [QUIET_DAYS].
 */
fun catalogParcels(
    mails: List<ShippingMail>,
    markedDelivered: Set<String>,
    removed: Set<String>,
    now: Long,
): List<Parcel> {
    val mailsByNumber = mails
        .flatMap { mail -> mail.parcels.map { parcel -> parcel to mail } }
        .groupBy({ (parcel, _) -> parcel.number }, { (parcel, mail) -> parcel to mail })

    return mailsByNumber
        .filterKeys { it !in removed }
        .map { (number, entries) ->
            val parcelNumber = entries.first().first
            val mailsAbout = entries.map { it.second }.distinctBy { it.key }.sortedByDescending { it.date }
            val first = mailsAbout.last()
            val status = mailsAbout.firstNotNullOfOrNull { mailStatus(it.subject, it.text) } ?: ParcelStatus.ON_ITS_WAY

            Parcel(
                number = number,
                carrier = parcelNumber.carrier,
                trackingUrl = parcelNumber.trackingUrl,
                shop = first.senderName,
                title = first.subject,
                firstSeen = first.date,
                lastSeen = mailsAbout.first().date,
                mailStatus = status,
                mails = mailsAbout.map { ParcelMail(it.key, it.date, it.senderName, it.subject) },
                isMarkedDelivered = number in markedDelivered,
            )
        }
        .filter { parcel -> isStillOfInterest(parcel, now) }
        .sortedByDescending { it.lastSeen }
}

private fun isStillOfInterest(parcel: Parcel, now: Long): Boolean {
    val daysQuiet = (now - parcel.lastSeen) / DAY_MILLIS
    val isDelivered = parcel.isMarkedDelivered || parcel.mailStatus == ParcelStatus.DELIVERED
    return if (isDelivered) daysQuiet < DELIVERED_SHOWN_DAYS else daysQuiet < QUIET_DAYS
}
