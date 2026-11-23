package net.thunderbird.feature.packages.internal

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeoutOrNull
import net.thunderbird.feature.packages.ParcelsArrivingToday
import net.thunderbird.feature.packages.ShippingMailSource

private const val SCAN_DAYS = 14
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
private val DOWNLOAD_TIMEOUT = 1.minutes

internal class DefaultParcelsArrivingToday(
    private val mailSource: ShippingMailSource,
    private val marks: ParcelMarks,
) : ParcelsArrivingToday {
    override suspend fun arrivingToday(startOfToday: Long): List<String> {
        // This morning's mail may be known only by its envelope, as Microsoft 365 mail is until it is opened; the
        // digest or "out for delivery" mail is read only once it is here. A slow or absent network must not hold up
        // the summary, which then tells what is already on the device.
        withTimeoutOrNull(DOWNLOAD_TIMEOUT) {
            mailSource.downloadMails(since = startOfToday, onProgress = { _, _ -> })
        }
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
