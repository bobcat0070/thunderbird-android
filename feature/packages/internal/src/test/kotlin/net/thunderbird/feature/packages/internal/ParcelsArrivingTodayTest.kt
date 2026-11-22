package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.feature.packages.ParcelCarrier

private const val HOUR = 60 * 60 * 1000L
private const val START_OF_TODAY = 1_795_000_000_000L

class ParcelsArrivingTodayTest {

    @Test
    fun `a parcel out for delivery in a mail from this morning should be arriving today`() {
        val parcel = parcel(shop = "Cedar Outfitters", lastSeen = START_OF_TODAY + 2 * HOUR)

        val result = arrivingToday(listOf(parcel), START_OF_TODAY)

        assertThat(result).containsExactly("Cedar Outfitters")
    }

    @Test
    fun `a parcel last said to be out for delivery yesterday should not be arriving today`() {
        val parcel = parcel(shop = "Harbor Books", lastSeen = START_OF_TODAY - 2 * HOUR)

        val result = arrivingToday(listOf(parcel), START_OF_TODAY)

        assertThat(result).isEmpty()
    }

    @Test
    fun `a parcel marked delivered or only on its way should not be arriving today`() {
        val delivered = parcel(shop = "Harbor Books", lastSeen = START_OF_TODAY + HOUR, isMarkedDelivered = true)
        val onItsWay = parcel(shop = "Lumen", lastSeen = START_OF_TODAY + HOUR, status = ParcelStatus.ON_ITS_WAY)

        val result = arrivingToday(listOf(delivered, onItsWay), START_OF_TODAY)

        assertThat(result).isEmpty()
    }

    @Test
    fun `two parcels from one shop should name the shop once and a parcel without a shop by its title`() {
        val first = parcel(number = "1Z0001", shop = "Cedar Outfitters", lastSeen = START_OF_TODAY + HOUR)
        val second = parcel(number = "1Z0002", shop = "Cedar Outfitters", lastSeen = START_OF_TODAY + HOUR)
        val noShop = parcel(number = "1Z0003", shop = null, title = "Trail shoes", lastSeen = START_OF_TODAY + HOUR)

        val result = arrivingToday(listOf(first, second, noShop), START_OF_TODAY)

        assertThat(result).containsExactly("Cedar Outfitters", "Trail shoes")
    }

    @Suppress("LongParameterList")
    private fun parcel(
        number: String = "1Z0001",
        shop: String?,
        title: String? = null,
        lastSeen: Long,
        status: ParcelStatus = ParcelStatus.OUT_FOR_DELIVERY,
        isMarkedDelivered: Boolean = false,
    ) = Parcel(
        number = number,
        carrier = ParcelCarrier.UPS,
        trackingUrl = "https://www.ups.com/track?tracknum=$number",
        shop = shop,
        title = title,
        firstSeen = lastSeen - 24 * HOUR,
        lastSeen = lastSeen,
        mailStatus = status,
        mails = emptyList(),
        isMarkedDelivered = isMarkedDelivered,
    )
}
