package net.thunderbird.feature.todaysummary.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val ZONE = ZoneId.of("America/Phoenix")

class TodaySummaryTimingTest {

    @Test
    fun `a time later today should come today`() {
        val now = ZonedDateTime.of(2026, 11, 22, 6, 0, 0, 0, ZONE)

        assertThat(millisUntilNext(now, "07:30")).isEqualTo((1.hours + 30.minutes).inWholeMilliseconds)
    }

    @Test
    fun `a time already past should come tomorrow`() {
        val now = ZonedDateTime.of(2026, 11, 22, 8, 0, 0, 0, ZONE)

        assertThat(millisUntilNext(now, "07:30")).isEqualTo((23.hours + 30.minutes).inWholeMilliseconds)
    }

    @Test
    fun `the time itself should count as past`() {
        val now = ZonedDateTime.of(2026, 11, 22, 7, 30, 0, 0, ZONE)

        assertThat(millisUntilNext(now, "07:30")).isEqualTo(24.hours.inWholeMilliseconds)
    }

    @Test
    fun `a time that cannot be read should mean half past seven`() {
        val now = ZonedDateTime.of(2026, 11, 22, 7, 0, 0, 0, ZONE)

        assertThat(millisUntilNext(now, "soon")).isEqualTo(30.minutes.inWholeMilliseconds)
        assertThat(millisUntilNext(now, "7:45")).isEqualTo(45.minutes.inWholeMilliseconds)
    }

    @Test
    fun `a long list should name a few and count the rest`() {
        assertThat(shortList(listOf("Dana", "Sam", "Alex", "Kim"), total = 6))
            .isEqualTo(listOf("Dana", "Sam", "Alex") to 3)
        assertThat(shortList(listOf("Dana"), total = 1)).isEqualTo(listOf("Dana") to 0)
    }
}
