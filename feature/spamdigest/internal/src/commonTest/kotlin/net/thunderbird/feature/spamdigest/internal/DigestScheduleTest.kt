package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.thunderbird.feature.spamdigest.SpamDigestTime

class DigestScheduleTest {
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `a digest should normally report on the whole local day before`() {
        val now = Instant.parse("2026-10-03T11:00:00Z") // 07:00 in New York

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 1))

        assertThat(testSubject).isEqualTo(
            DigestPeriod(
                firstDate = LocalDate(2026, 10, 2),
                lastDate = LocalDate(2026, 10, 2),
                start = Instant.parse("2026-10-02T04:00:00Z"),
                end = Instant.parse("2026-10-03T04:00:00Z"),
            ),
        )
    }

    @Test
    fun `a digest should cover the days whose digests did not go out`() {
        // The digests for Oct 4 and Oct 5 never went out; their spam must not go unreported.
        val now = Instant.parse("2026-10-06T11:00:00Z")

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 3))

        assertThat(testSubject?.firstDate).isEqualTo(LocalDate(2026, 10, 4))
        assertThat(testSubject?.lastDate).isEqualTo(LocalDate(2026, 10, 5))
        assertThat(testSubject?.start).isEqualTo(Instant.parse("2026-10-04T04:00:00Z"))
        assertThat(testSubject?.end).isEqualTo(Instant.parse("2026-10-06T04:00:00Z"))
    }

    @Test
    fun `a digest after a long gap should cover only the last week`() {
        val now = Instant.parse("2026-10-20T11:00:00Z")

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 1))

        assertThat(testSubject?.firstDate).isEqualTo(LocalDate(2026, 10, 13))
        assertThat(testSubject?.lastDate).isEqualTo(LocalDate(2026, 10, 19))
    }

    @Test
    fun `the first digest should report on yesterday only`() {
        val now = Instant.parse("2026-10-03T11:00:00Z")

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = null)

        assertThat(testSubject?.firstDate).isEqualTo(LocalDate(2026, 10, 2))
        assertThat(testSubject?.lastDate).isEqualTo(LocalDate(2026, 10, 2))
    }

    @Test
    fun `nothing should be due before the digest time when yesterday's day is not over for it`() {
        // At 06:00 yesterday's digest is not due yet; the day before was reported on.
        val now = Instant.parse("2026-10-03T10:00:00Z")

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 1))

        assertThat(testSubject).isEqualTo(null)
    }

    @Test
    fun `an alarm going off a moment early should still report on yesterday`() {
        val now = Instant.parse("2026-10-03T10:59:59Z") // 06:59:59 in New York

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 1))

        assertThat(testSubject?.lastDate).isEqualTo(LocalDate(2026, 10, 2))
    }

    @Test
    fun `a day should be 25 hours long when the clocks go back`() {
        val now = Instant.parse("2026-11-02T12:00:00Z")

        val testSubject = reportingPeriod(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 31))

        assertThat(testSubject?.lastDate).isEqualTo(LocalDate(2026, 11, 1))
        assertThat(testSubject?.let { it.end - it.start }).isEqualTo(25.hours)
    }

    @Test
    fun `a digest should be overdue once a day passed its time unreported`() {
        val now = Instant.parse("2026-10-03T14:00:00Z") // 10:00 in New York

        assertThat(isDigestOverdue(now, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 1))).isTrue()
    }

    @Test
    fun `a digest should not be overdue when sent, not yet due, or never sent`() {
        val afterTime = Instant.parse("2026-10-03T14:00:00Z")

        assertThat(isDigestOverdue(afterTime, newYork, SEVEN, lastSentDay = LocalDate(2026, 10, 2))).isFalse()
        assertThat(
            isDigestOverdue(Instant.parse("2026-10-03T10:00:00Z"), newYork, SEVEN, LocalDate(2026, 10, 1)),
        ).isFalse()
        // Turning the digest on does not send one straight away; the first comes at its time.
        assertThat(isDigestOverdue(afterTime, newYork, SEVEN, lastSentDay = null)).isFalse()
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

    private companion object {
        val SEVEN = SpamDigestTime(hour = 7, minute = 0)
    }
}
