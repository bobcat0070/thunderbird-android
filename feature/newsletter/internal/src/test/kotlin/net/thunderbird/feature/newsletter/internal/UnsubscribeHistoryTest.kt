package net.thunderbird.feature.newsletter.internal

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import net.thunderbird.feature.newsletter.StillMailing
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UnsubscribeHistoryTest {
    private val testSubject = SharedPreferencesUnsubscribeHistory(RuntimeEnvironment.getApplication())
    private val followUp = StoreUnsubscribeFollowUp(testSubject)

    @Test
    fun `mail long after unsubscribing is pointed out`() {
        testSubject.recordUnsubscribe("News@Shop.example", UNSUBSCRIBED_AT)

        val result = followUp.check("news@shop.example", UNSUBSCRIBED_AT + 6.days.inWholeMilliseconds)

        assertThat(result).isEqualTo(StillMailing("news@shop.example", UNSUBSCRIBED_AT))
    }

    @Test
    fun `mail within the grace period is let be`() {
        testSubject.recordUnsubscribe("news@shop.example", UNSUBSCRIBED_AT)

        val result = followUp.check("news@shop.example", UNSUBSCRIBED_AT + (5.days - 1.hours).inWholeMilliseconds)

        assertThat(result).isNull()
    }

    @Test
    fun `mail from before the unsubscribe is let be`() {
        testSubject.recordUnsubscribe("news@shop.example", UNSUBSCRIBED_AT)

        assertThat(followUp.check("news@shop.example", UNSUBSCRIBED_AT - 30.days.inWholeMilliseconds)).isNull()
    }

    @Test
    fun `other addresses at the domain are let be`() {
        testSubject.recordUnsubscribe("news@shop.example", UNSUBSCRIBED_AT)

        assertThat(followUp.check("orders@shop.example", UNSUBSCRIBED_AT + 30.days.inWholeMilliseconds)).isNull()
    }

    @Test
    fun `a forgotten unsubscribe is let be`() {
        testSubject.recordUnsubscribe("news@shop.example", UNSUBSCRIBED_AT)

        testSubject.forget("NEWS@shop.example")

        assertThat(followUp.check("news@shop.example", UNSUBSCRIBED_AT + 30.days.inWholeMilliseconds)).isNull()
    }

    @Test
    fun `import keeps valid entries and later dates`() {
        testSubject.recordUnsubscribe("news@shop.example", UNSUBSCRIBED_AT)

        testSubject.importUnsubscribes(
            mapOf(
                "news@shop.example" to UNSUBSCRIBED_AT - 1,
                "Deals@Store.example" to UNSUBSCRIBED_AT + 1,
                "not an address" to UNSUBSCRIBED_AT,
                "zero@store.example" to 0L,
            ),
        )

        assertThat(testSubject.exportUnsubscribes().entries.map { it.key to it.value }).containsOnly(
            "news@shop.example" to UNSUBSCRIBED_AT,
            "deals@store.example" to UNSUBSCRIBED_AT + 1,
        )
    }

    private companion object {
        const val UNSUBSCRIBED_AT = 1_790_000_000_000L
    }
}
