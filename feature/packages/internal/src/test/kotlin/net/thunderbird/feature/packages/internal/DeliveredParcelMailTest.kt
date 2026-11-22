package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1_795_000_000_000L
private const val CUTOFF = NOW - 14 * DAY

class DeliveredParcelMailTest {

    @Test
    fun `every mail about a parcel delivered before the cutoff should count`() {
        val shipped = mail("1", daysAgo = 25, subject = "Your order has shipped", "1Z0001")
        val delivered = mail("2", daysAgo = 20, subject = "Your package was delivered", "1Z0001")

        val result = deliveredMailKeys(listOf(shipped, delivered), markedDelivered = emptySet(), CUTOFF)

        assertThat(result).containsOnly("1", "2")
    }

    @Test
    fun `a parcel delivered recently should not count yet`() {
        val delivered = mail("1", daysAgo = 3, subject = "Your package was delivered", "1Z0001")

        val result = deliveredMailKeys(listOf(delivered), markedDelivered = emptySet(), CUTOFF)

        assertThat(result).isEmpty()
    }

    @Test
    fun `a parcel still on its way should not count however old`() {
        val shipped = mail("1", daysAgo = 40, subject = "Your order has shipped", "1Z0001")

        val result = deliveredMailKeys(listOf(shipped), markedDelivered = emptySet(), CUTOFF)

        assertThat(result).isEmpty()
    }

    @Test
    fun `a parcel the reader marked delivered should count`() {
        val shipped = mail("1", daysAgo = 30, subject = "Your order has shipped", "1Z0001")

        val result = deliveredMailKeys(listOf(shipped), markedDelivered = setOf("1Z0001"), CUTOFF)

        assertThat(result).containsOnly("1")
    }

    @Test
    fun `a mail about a delivered parcel and one still coming should not count`() {
        val both = mail("1", daysAgo = 30, subject = "Your order has shipped", "1Z0001", "1Z0002")
        val delivered = mail("2", daysAgo = 20, subject = "Your package was delivered", "1Z0001")

        val result = deliveredMailKeys(listOf(both, delivered), markedDelivered = emptySet(), CUTOFF)

        assertThat(result).containsOnly("2")
    }

    private fun mail(key: String, daysAgo: Int, subject: String, vararg numbers: String) = ShippingMail(
        key = key,
        date = NOW - daysAgo * DAY,
        senderName = "Harbor Books",
        subject = subject,
        parcels = numbers.map { ParcelNumber(ParcelCarrier.UPS, it, "https://www.ups.example/track?n=$it") },
        text = subject,
    )
}
