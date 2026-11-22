package net.thunderbird.feature.packages.internal

import net.thunderbird.feature.packages.ParcelsArrivingToday
import net.thunderbird.feature.packages.ShippingMailSource

private const val SCAN_DAYS = 14
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

internal class DefaultParcelsArrivingToday(
    private val mailSource: ShippingMailSource,
    private val marks: ParcelMarks,
) : ParcelsArrivingToday {
    override suspend fun arrivingToday(startOfToday: Long): List<String> {
        val mails = mailSource.shippingMails(since = startOfToday - SCAN_DAYS * MILLIS_PER_DAY)
        val parcels = catalogParcels(mails, marks.markedDelivered(), marks.removed(), now = startOfToday)
        return arrivingToday(parcels, startOfToday)
    }
}

/**
 * The names of the parcels in [parcels] out for delivery today: not marked delivered, with their newest mail sent
 * today and saying so.
 */
internal fun arrivingToday(parcels: List<Parcel>, startOfToday: Long): List<String> = parcels
    .filter { parcel ->
        !parcel.isMarkedDelivered &&
            parcel.mailStatus == ParcelStatus.OUT_FOR_DELIVERY &&
            parcel.lastSeen >= startOfToday
    }
    .mapNotNull { parcel -> parcel.shop ?: parcel.title }
    .distinct()
