package net.thunderbird.feature.impersonation.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.thunderbird.core.testing.TestClock
import net.thunderbird.feature.impersonation.KnownSenders
import net.thunderbird.feature.impersonation.KnownSendersSource

class CachingImpersonationCheckerTest {
    private val clock = TestClock(Instant.parse("2026-10-04T12:00:00Z"))
    private var knownSenders = KnownSenders(emptyMap(), emptySet(), emptySet())
    private var readings = 0
    private val source = KnownSendersSource {
        readings++
        knownSenders
    }
    private val testSubject = CachingImpersonationChecker(source, clock)

    @Test
    fun `should read who the reader knows once for many checks`() {
        repeat(5) { testSubject.check("PayPal", "service@paypa1.com") }

        assertThat(readings).isEqualTo(1)
    }

    @Test
    fun `should read again once the reading is half an hour old`() {
        assertThat(testSubject.check("PayPal", "service@paypa1.com")).isNull()
        knownSenders = KnownSenders(emptyMap(), emptySet(), setOf("paypal.com"))

        clock.advanceTimeBy(29.minutes)
        assertThat(testSubject.check("PayPal", "service@paypa1.com")).isNull()

        clock.advanceTimeBy(2.minutes)
        assertThat(testSubject.check("PayPal", "service@paypa1.com")).isNotNull()
        assertThat(readings).isEqualTo(2)
    }
}
