package net.thunderbird.app.common.feature.impersonation

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test

class NightlyScanTimeTest {
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `before three in the morning the scan should be later the same night`() {
        val now = at(2026, 10, 18, hour = 1, minute = 30)

        val testSubject = nextNightlyScanAt(now, newYork)

        assertThat(testSubject).isEqualTo(at(2026, 10, 18, hour = 3, minute = 0))
    }

    @Test
    fun `after three in the morning the scan should be the next night`() {
        val now = at(2026, 10, 18, hour = 14, minute = 0)

        val testSubject = nextNightlyScanAt(now, newYork)

        assertThat(testSubject).isEqualTo(at(2026, 10, 19, hour = 3, minute = 0))
    }

    @Test
    fun `at exactly three the scan should be the next night`() {
        val now = at(2026, 10, 18, hour = 3, minute = 0)

        val testSubject = nextNightlyScanAt(now, newYork)

        assertThat(testSubject).isEqualTo(at(2026, 10, 19, hour = 3, minute = 0))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime(year, month, day, hour, minute).toInstant(newYork)
}
