package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.single
import kotlin.test.Test
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 100 * DAY

class ParcelCatalogTest {
    private val ups = ParcelNumber(ParcelCarrier.UPS, "1Z999AA10123456784", "https://ups.example/1Z")
    private val usps = ParcelNumber(ParcelCarrier.USPS, "9400100000000000000013", "https://usps.example/94")

    @Test
    fun `status should be read from the subject first`() {
        assertThat(mailStatus("Your package is out for delivery", "")).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
        assertThat(mailStatus("Delivered: your order", "")).isEqualTo(ParcelStatus.DELIVERED)
        assertThat(mailStatus("UPS Update: Delivery Exception", "")).isEqualTo(ParcelStatus.PROBLEM)
        assertThat(mailStatus("Your order will be delivered Tuesday", "")).isNull()
    }

    @Test
    fun `promises in the text should not count as a status`() {
        val text = "We'll let you know when it's out for delivery. Shipping delays may occur. Deliver to: home"

        assertThat(mailStatus("Your order has shipped", text)).isNull()
        assertThat(mailStatus("Shipping update", "Your package was delivered at 2:14 PM"))
            .isEqualTo(ParcelStatus.DELIVERED)
    }

    @Test
    fun `mails naming the same number should become one parcel, newest status first`() {
        val mails = listOf(
            mail("1", date = NOW - 3 * DAY, subject = "Your order has shipped", parcels = listOf(ups), sender = "Shop"),
            mail("2", date = NOW - 1 * DAY, subject = "Out for delivery", parcels = listOf(ups), sender = "UPS"),
        )

        val parcel = catalogParcels(mails, emptySet(), emptySet(), NOW).single()

        assertThat(parcel.number).isEqualTo(ups.number)
        assertThat(parcel.mailStatus).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
        assertThat(parcel.shop).isEqualTo("Shop")
        assertThat(parcel.title).isEqualTo("Your order has shipped")
        assertThat(parcel.mails.map { it.key }).containsExactly("2", "1")
    }

    @Test
    fun `one mail naming two numbers should make two parcels`() {
        val mails = listOf(mail("1", date = NOW - DAY, subject = "Shipped in 2 boxes", parcels = listOf(ups, usps)))

        val parcels = catalogParcels(mails, emptySet(), emptySet(), NOW)

        assertThat(parcels.map { it.number }.toSet()).isEqualTo(setOf(ups.number, usps.number))
    }

    @Test
    fun `removed, long delivered and long quiet parcels should be left out`() {
        val mails = listOf(
            mail("1", date = NOW - DAY, subject = "Shipped", parcels = listOf(ups)),
            mail("2", date = NOW - (DELIVERED_SHOWN_DAYS + 1) * DAY, subject = "Delivered", parcels = listOf(usps)),
        )

        assertThat(catalogParcels(mails, emptySet(), removed = setOf(ups.number), now = NOW)).isEmpty()

        val quiet = listOf(mail("3", date = NOW - (QUIET_DAYS + 1) * DAY, subject = "Shipped", parcels = listOf(ups)))
        assertThat(catalogParcels(quiet, emptySet(), emptySet(), NOW)).isEmpty()
    }

    @Test
    fun `a parcel the reader marked delivered should say so`() {
        val mails = listOf(mail("1", date = NOW - DAY, subject = "Shipped", parcels = listOf(ups)))

        val parcel = catalogParcels(mails, markedDelivered = setOf(ups.number), removed = emptySet(), now = NOW)

        assertThat(parcel).single().transform { it.isMarkedDelivered }.isEqualTo(true)
    }

    private fun mail(
        key: String,
        date: Long,
        subject: String,
        parcels: List<ParcelNumber>,
        sender: String? = "Shop",
    ) = ShippingMail(
        key = key,
        date = date,
        senderName = sender,
        subject = subject,
        parcels = parcels,
        text = subject,
    )
}
