package net.thunderbird.feature.spamdigest.internal

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.thunderbird.feature.spamdigest.SpamDigestTime

/**
 * The calendar day a digest reports on, and the instants it runs between.
 *
 * @param start the first instant of [date], inclusive.
 * @param end the first instant of the following day, exclusive.
 */
internal data class DigestDay(
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
)

/**
 * The day before the one [now] falls on, in [timeZone].
 *
 * Built from the starts of the two days rather than by subtracting 24 hours, so the day the clocks change is
 * still covered exactly once: 23 or 25 hours long, as it really was.
 */
internal fun previousDay(now: Instant, timeZone: TimeZone): DigestDay {
    val today = now.toLocalDateTime(timeZone).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)

    return DigestDay(
        date = yesterday,
        start = yesterday.atStartOfDayIn(timeZone),
        end = today.atStartOfDayIn(timeZone),
    )
}

/**
 * Whether a digest whose alarm should already have gone off was missed - the phone was off, or the app was stopped
 * - and should be sent now rather than waiting a day.
 *
 * @param scheduledFor when the last alarm was set to go off, or `null` when none was set.
 * @param lastSentDay the day the last digest reported on.
 */
internal fun isDigestMissed(
    scheduledFor: Instant?,
    now: Instant,
    lastSentDay: LocalDate?,
    timeZone: TimeZone,
): Boolean {
    if (scheduledFor == null || scheduledFor > now) return false

    return lastSentDay != previousDay(scheduledFor, timeZone).date
}

/**
 * The next time strictly after [now] that the clock in [timeZone] reads [time].
 *
 * On a day where [time] does not exist because the clocks went forward over it, this is the moment the clocks
 * reach it after the jump, which is when a person would expect it.
 */
internal fun nextRunAt(now: Instant, timeZone: TimeZone, time: SpamDigestTime): Instant {
    val today = now.toLocalDateTime(timeZone).date
    val localTime = LocalTime(time.hour, time.minute)

    val todayAt = LocalDateTime(today, localTime).toInstant(timeZone)
    if (todayAt > now) return todayAt

    return LocalDateTime(today.plus(1, DateTimeUnit.DAY), localTime).toInstant(timeZone)
}
