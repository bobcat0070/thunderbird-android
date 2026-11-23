package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail

private const val TODAY_NUMBER = "9400111899223855100123"
private const val LATER_NUMBER = "9200190303845628730068"

/**
 * A made-up digest, laid out as the app turns the real one's HTML into text.
 */
private val DIGEST_TEXT = """
    COMING TO YOU SOON

    Hi, SAM!

    You have 1 mailpiece(s) and 2 inbound package(s) arriving soon.

    MAIL

    View Dashboard

    Expected Today

    1 item(s)

    PACKAGES

    View Dashboard

    Expected Today

    1 item(s)

    FROM: CEDAR OUTFITTERS

    $TODAY_NUMBER

    $TODAY_NUMBER

    Expected 1-2 Days

    1 item(s)

    FROM: HARBOR BOOKS

    $LATER_NUMBER

    $LATER_NUMBER

    Awaiting From Sender

    0 item(s)

    No packages are available to display.
""".trimIndent()

class InformedDeliveryTest {
    @Test
    fun `a package under Expected Today should be out for delivery`() {
        assertThat(digestStatus(DIGEST_TEXT, TODAY_NUMBER)).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
    }

    @Test
    fun `a package expected in a day or two should be on its way`() {
        assertThat(digestStatus(DIGEST_TEXT, LATER_NUMBER)).isEqualTo(ParcelStatus.ON_ITS_WAY)
    }

    @Test
    fun `a package the digest does not list should have no status from it`() {
        assertThat(digestStatus(DIGEST_TEXT, "9400111899223855100000")).isNull()
    }

    @Test
    fun `each package should be from the shipper named above it`() {
        assertThat(digestShipper(DIGEST_TEXT, TODAY_NUMBER)).isEqualTo("CEDAR OUTFITTERS")
        assertThat(digestShipper(DIGEST_TEXT, LATER_NUMBER)).isEqualTo("HARBOR BOOKS")
    }

    @Test
    fun `a package without a shipper line should not take the one of the package above it`() {
        val text = DIGEST_TEXT.replace("FROM: HARBOR BOOKS", "")

        assertThat(digestShipper(text, LATER_NUMBER)).isNull()
    }

    @Test
    fun `only mail from Informed Delivery should be read as a digest`() {
        val digest = mail(address = "USPSInformeddelivery@email.informeddelivery.usps.com")
        val tracking = mail(address = "auto-reply@usps.com")

        assertThat(isInformedDeliveryDigest(digest)).isTrue()
        assertThat(isInformedDeliveryDigest(tracking)).isFalse()
    }

    @Test
    fun `the catalog should give each package of a digest its own status and shipper`() {
        val digest = mail(address = "USPSInformeddelivery@email.informeddelivery.usps.com", text = DIGEST_TEXT)

        val parcels = catalogParcels(listOf(digest), markedDelivered = emptySet(), removed = emptySet(), now = 0L)
            .associateBy { it.number }

        assertThat(parcels.getValue(TODAY_NUMBER).mailStatus).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
        assertThat(parcels.getValue(TODAY_NUMBER).shop).isEqualTo("CEDAR OUTFITTERS")
        assertThat(parcels.getValue(LATER_NUMBER).mailStatus).isEqualTo(ParcelStatus.ON_ITS_WAY)
        assertThat(parcels.getValue(LATER_NUMBER).shop).isEqualTo("HARBOR BOOKS")
    }

    private fun mail(address: String, text: String = "") = ShippingMail(
        key = "1",
        date = 0L,
        senderName = "USPS Informed Delivery",
        subject = "Your Daily Digest for Mon, 11/23 is ready to view",
        parcels = listOf(
            ParcelNumber(
                ParcelCarrier.USPS,
                TODAY_NUMBER,
                "https://usps.example/1",
            ),
            ParcelNumber(
                ParcelCarrier.USPS,
                LATER_NUMBER,
                "https://usps.example/2",
            ),
        ),
        text = text,
        senderAddress = address,
    )
}
