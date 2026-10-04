package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.thunderbird.feature.spamdigest.SpamDigestTime

class DigestScheduleTest {
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `previousDay should span the whole local day before now`() {
        val now = Instant.parse("2026-10-03T11:00:00Z") // 07:00 in New York

        val testSubject = previousDay(now, newYork)

        assertThat(testSubject).isEqualTo(
            DigestDay(
                date = LocalDate(2026, 10, 2),
                start = Instant.parse("2026-10-02T04:00:00Z"),
                end = Instant.parse("2026-10-03T04:00:00Z"),
            ),
        )
    }

    @Test
    fun `previousDay should be 25 hours long on the day the clocks go back`() {
        val now = Instant.parse("2026-11-02T12:00:00Z")

        val testSubject = previousDay(now, newYork)

        assertThat(testSubject.date).isEqualTo(LocalDate(2026, 11, 1))
        assertThat(testSubject.end - testSubject.start).isEqualTo(25.hours)
    }

    @Test
    fun `nextRunAt should be later today when the time has not passed yet`() {
        val now = Instant.parse("2026-10-03T09:00:00Z") // 05:00 in New York

        val testSubject = nextRunAt(now, newYork, SpamDigestTime(hour = 7, minute = 0))

        assertThat(testSubject).isEqualTo(Instant.parse("2026-10-03T11:00:00Z"))
    }

    @Test
    fun `nextRunAt should be tomorrow when it is exactly the time`() {
        val now = Instant.parse("2026-10-03T11:00:00Z") // 07:00 in New York

        val testSubject = nextRunAt(now, newYork, SpamDigestTime(hour = 7, minute = 0))

        assertThat(testSubject).isEqualTo(Instant.parse("2026-10-04T11:00:00Z"))
    }

    @Test
    fun `nextRunAt should keep the local time across a clock change`() {
        val now = Instant.parse("2026-10-31T12:00:00Z") // Saturday 08:00 in New York, before the clocks go back

        val testSubject = nextRunAt(now, newYork, SpamDigestTime(hour = 7, minute = 30))

        assertThat(testSubject).isEqualTo(Instant.parse("2026-11-01T12:30:00Z"))
    }
}
