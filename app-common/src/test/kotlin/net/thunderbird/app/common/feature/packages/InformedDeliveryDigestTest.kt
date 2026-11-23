package net.thunderbird.app.common.feature.packages

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.fsck.k9.message.extractors.findTrackingNumbersInText
import com.fsck.k9.message.html.HtmlConverter
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail
import net.thunderbird.feature.packages.internal.ParcelStatus
import net.thunderbird.feature.packages.internal.catalogParcels
import org.junit.Test

private const val TODAY_NUMBER = "9400111899223855100123"
private const val LATER_NUMBER = "9200190303845628730068"
private const val SUBJECT = "Your Daily Digest for Mon, 11/23 is ready to view"

/**
 * A long tracking link, as the digest puts on every button and number.
 */
private val CLICK = "https://informeddelivery.example/digest/click?a=" + "Xy0".repeat(120)

/**
 * A made-up digest built as the real one is: headings and shippers in tables, each package twice (for wide and narrow
 * screens), and a long link on everything, a dozen of them before the packages.
 */
private val DIGEST_HTML = buildString {
    append("<div><table><tr><td><h1>COMING TO YOU SOON</h1></td></tr></table>")
    append("<p>Hi, SAM!</p><p>You have 1 mailpiece(s) and 2 inbound package(s) arriving soon.</p>")
    repeat(12) { append("""<a href="$CLICK"> </a>""") }
    append("""<table><tr><td><h1>MAIL</h1></td><td><a href="$CLICK">View Dashboard </a></td></tr></table>""")
    append("""<table><tr><td><h2>Expected Today</h2></td><td><p>1 item(s) </p></td></tr></table>""")
    append("""<table><tr><td><h1>PACKAGES</h1></td><td><a href="$CLICK">View Dashboard </a></td></tr></table>""")
    append(section("Expected Today", "CEDAR OUTFITTERS", TODAY_NUMBER))
    append(section("Expected 1-2 Days", "HARBOR BOOKS", LATER_NUMBER))
    append("""<table><tr><td><h2>Awaiting From Sender</h2></td><td><p>0 item(s) </p></td></tr></table>""")
    append("<p><strong>No packages are available to display.</strong></p></div>")
}

private fun section(heading: String, shipper: String, number: String) = """
    <table><tr><td><h2>$heading</h2></td><td><p>1 item(s) </p></td></tr></table>
    <table><tr><td><p>FROM: <b>$shipper</b></p></td>
    <td><p><a href="$CLICK">$number</a></p></td></tr>
    <tr><td style="display:none"><p><a href="$CLICK">$number</a></p></td></tr></table>
""".trimIndent()

class InformedDeliveryDigestTest {
    @Test
    fun `a digest's packages should be found with the status and shipper of their own section`() {
        val fullText = SUBJECT + "\n" + HtmlConverter.htmlToText(DIGEST_HTML)
        val numbers = findTrackingNumbersInText(fullText)
        val mail = ShippingMail(
            key = "1",
            date = 0L,
            senderName = "USPS Informed Delivery",
            subject = SUBJECT,
            parcels = numbers.map { ParcelNumber(ParcelCarrier.USPS, it.number, it.url) },
            text = keptShippingText(fullText),
            senderAddress = "USPSInformeddelivery@email.informeddelivery.usps.com",
        )

        val parcels = catalogParcels(listOf(mail), markedDelivered = emptySet(), removed = emptySet(), now = 0L)
            .associateBy { it.number }

        assertThat(numbers.map { it.number }.distinct()).containsExactly(TODAY_NUMBER, LATER_NUMBER)
        assertThat(parcels.getValue(TODAY_NUMBER).mailStatus).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
        assertThat(parcels.getValue(TODAY_NUMBER).shop).isEqualTo("CEDAR OUTFITTERS")
        assertThat(parcels.getValue(LATER_NUMBER).mailStatus).isEqualTo(ParcelStatus.ON_ITS_WAY)
        assertThat(parcels.getValue(LATER_NUMBER).shop).isEqualTo("HARBOR BOOKS")
    }

    @Test
    fun `link targets should not be kept, so a long mail keeps more of its words`() {
        val text = keptShippingText("Track it <https://carrier.example/track?n=1> or call us")

        assertThat(text).isEqualTo("Track it or call us")
    }
}
