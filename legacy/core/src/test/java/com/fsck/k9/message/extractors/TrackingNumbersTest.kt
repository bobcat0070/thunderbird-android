package com.fsck.k9.message.extractors

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.testing.message.buildMessage
import kotlin.test.Test

class TrackingNumbersTest {
    @Test
    fun `a UPS number should be found, written in groups or not`() {
        assertThat(findTrackingNumberInText("Your package shipped. Tracking number: 1Z999AA10123456784"))
            .isEqualTo(TrackingNumber(Carrier.UPS, "1Z999AA10123456784"))
        assertThat(findTrackingNumberInText("UPS tracking: 1Z 999 AA1 01 2345 6784."))
            .isEqualTo(TrackingNumber(Carrier.UPS, "1Z999AA10123456784"))
    }

    @Test
    fun `a USPS number should be found, written in groups or not`() {
        assertThat(findTrackingNumberInText("Track your USPS package: 9400 1000 0000 0000 0000 13"))
            .isEqualTo(TrackingNumber(Carrier.USPS, "9400100000000000000013"))
        assertThat(findTrackingNumberInText("Shipped with tracking 9205500000000000123458"))
            .isEqualTo(TrackingNumber(Carrier.USPS, "9205500000000000123458"))
        assertThat(findTrackingNumberInText("Your parcel EA123456785US is on its way"))
            .isEqualTo(TrackingNumber(Carrier.USPS, "EA123456785US"))
    }

    @Test
    fun `FedEx and DHL numbers should only be found next to the carrier's name`() {
        assertThat(findTrackingNumberInText("Shipped via FedEx, tracking number 123456789012"))
            .isEqualTo(TrackingNumber(Carrier.FEDEX, "123456789012"))
        assertThat(findTrackingNumberInText("DHL Express waybill: 1234567891. Delivery tomorrow."))
            .isEqualTo(TrackingNumber(Carrier.DHL, "1234567891"))
        assertThat(findTrackingNumberInText("Your order 123456789012 has shipped")).isNull()
        assertThat(findTrackingNumberInText("Your order 1234567891 has shipped")).isNull()
    }

    @Test
    fun `numbers that fail the carrier's check should be left alone`() {
        assertThat(findTrackingNumberInText("Tracking number: 1Z999AA10123456785")).isNull()
        assertThat(findTrackingNumberInText("Tracking number: 9400100000000000000014")).isNull()
        assertThat(findTrackingNumberInText("FedEx tracking 123456789013")).isNull()
    }

    @Test
    fun `a mail that says nothing about shipping should be left alone`() {
        assertThat(findTrackingNumberInText("Invoice 1Z999AA10123456784 is attached")).isNull()
    }

    @Test
    fun `the number that comes first should be taken`() {
        val text = "USPS tracking 9400100000000000000013, and the UPS part 1Z999AA10123456784"

        assertThat(findTrackingNumberInText(text)?.carrier).isEqualTo(Carrier.USPS)
    }

    @Test
    fun `every number in a mail should be found once, in order`() {
        val text = "Your order shipped in two packages. UPS 1Z999AA10123456784 and USPS 9400100000000000000013. " +
            "Track 1Z999AA10123456784 any time."

        assertThat(findTrackingNumbersInText(text)).containsExactly(
            TrackingNumber(Carrier.UPS, "1Z999AA10123456784"),
            TrackingNumber(Carrier.USPS, "9400100000000000000013"),
        )
    }

    @Test
    fun `an HTML-only message should be read as text`() {
        val testSubject = buildMessage {
            header("Content-Type", "text/html; charset=utf-8")
            textBody(
                "<p>Your order has shipped!</p>" +
                    "<p>Tracking: <a href=\"https://t.example/x\">1Z999AA10123456784</a>",
            )
        }

        val trackingNumber = findTrackingNumber(testSubject, subject = "Shipped: your order")

        assertThat(trackingNumber?.url).isEqualTo("https://www.ups.com/track?tracknum=1Z999AA10123456784")
    }
}
