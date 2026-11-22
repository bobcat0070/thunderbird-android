package net.thunderbird.feature.packages.internal

import net.thunderbird.feature.packages.DeliveredParcelMail
import net.thunderbird.feature.packages.ShippingMail
import net.thunderbird.feature.packages.ShippingMailSource

/**
 * How far back shipping mail is read for delivered parcels: well past when a delivered parcel's mail is of no more use.
 */
internal const val DELIVERED_SCAN_DAYS = 120

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

internal class DefaultDeliveredParcelMail(
    private val mailSource: ShippingMailSource,
    private val marks: ParcelMarks,
) : DeliveredParcelMail {
    override suspend fun deliveredMailKeys(deliveredBefore: Long): Set<String> {
        val since = deliveredBefore - DELIVERED_SCAN_DAYS * MILLIS_PER_DAY
        return deliveredMailKeys(mailSource.shippingMails(since), marks.markedDelivered(), deliveredBefore)
    }
}

/**
 * The keys of the mails in [mails] whose parcels were all delivered and last mentioned before [deliveredBefore].
 */
internal fun deliveredMailKeys(
    mails: List<ShippingMail>,
    markedDelivered: Set<String>,
    deliveredBefore: Long,
): Set<String> {
    val mailsByNumber = mails
        .flatMap { mail -> mail.parcels.map { parcel -> parcel.number to mail } }
        .groupBy({ it.first }, { it.second })

    val doneNumbers = mailsByNumber.filter { (number, mailsAbout) ->
        val newestFirst = mailsAbout.sortedByDescending { it.date }
        val isDelivered = number in markedDelivered ||
            newestFirst.firstNotNullOfOrNull { mailStatus(it.subject, it.text) } == ParcelStatus.DELIVERED
        isDelivered && newestFirst.first().date < deliveredBefore
    }.keys

    return mails
        .filter { mail -> mail.parcels.isNotEmpty() && mail.parcels.all { it.number in doneNumbers } }
        .mapTo(mutableSetOf()) { it.key }
}
