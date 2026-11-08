package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail

class ParcelSenderTest {
    private val ups = ParcelNumber(ParcelCarrier.UPS, "1Z999AA10123456784", "https://ups.example/1Z")

    @Test
    fun `the shop that mailed about a parcel should be who it is from, even after the carrier wrote`() {
        val mails = listOf(
            mail(sender = "UPS", address = "mcinfo@ups.com", text = "From ACME GADGETS LIMITED"),
            mail(sender = "Example Shop", address = "orders@shop.example", text = "Your order has shipped"),
        )

        val result = parcelSender(mails)

        assertThat(result).isEqualTo("Example Shop")
    }

    @Test
    fun `when only the carrier wrote, the shipper it names should be who the parcel is from`() {
        val mails = listOf(
            mail(
                sender = "UPS",
                address = "mcinfo@ups.com",
                text = "Your Package Has Cleared Customs\nHi,\nYour package cleared customs and is on its way!\n" +
                    "From ACME GADGETS LIMITED\nTrack Your Package\nShip To",
            ),
            mail(
                sender = "FedEx Delivery Manager",
                address = "TrackingUpdates@fedex.com",
                text = "Your shipment from EXAMPLE AUTO PARTS LLC is out for delivery today.",
            ),
        )

        val result = listOf(parcelSender(mails), parcelSender(mails.asReversed()))

        assertThat(result).containsExactly("ACME GADGETS LIMITED", "EXAMPLE AUTO PARTS LLC")
    }

    @Test
    fun `the shipper a USPS tracking mail names should be who the parcel is from`() {
        val mails = listOf(
            mail(
                sender = "USPS Tracking",
                address = "auto-reply@tracking.usps.com",
                text = "USPS Expected Delivery on Friday, May 1, 2026 Between 9:00am and 11:00am\n" +
                    "Tracking Number: 9400100000000000000013\n" +
                    "Package Shipped from: EXAMPLE PHARMACY\nOut for Delivery",
            ),
        )

        val result = parcelSender(mails)

        assertThat(result).isEqualTo("EXAMPLE PHARMACY")
    }

    @Test
    fun `when the carrier names no shipper, the carrier should be who the parcel is from`() {
        val mails = listOf(mail(sender = "UPS", address = "pkginfo@ups.com", text = "Your package is on its way"))

        val result = parcelSender(mails)

        assertThat(result).isEqualTo("UPS")
    }

    @Test
    fun `a named shipper should be shown as one short plain line`() {
        val texts = listOf(
            "Shipper: Acme‎  Tools.",
            "From " + "A".repeat(100),
            "From: someone@example.com",
        )

        val result = texts.map(::shipperNamedIn)

        assertThat(result).containsExactly("Acme Tools", null, null)
    }

    private fun mail(sender: String, address: String, text: String) = ShippingMail(
        key = sender,
        date = 0,
        senderName = sender,
        subject = null,
        parcels = listOf(ups),
        text = text,
        senderAddress = address,
    )
}
