package net.thunderbird.feature.spamdigest.internal

import kotlin.time.Duration.Companion.minutes
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
 * How much earlier than the chosen time a digest still counts as that day's. An exact alarm can go off a moment
 * early, and that run must still report on yesterday.
 */
private val EARLY_RUN_TOLERANCE = 10.minutes

/**
 * The most days one digest reports on. After a longer gap - the phone off for a fortnight - the digest covers the
 * last week rather than everything since the last one.
 */
internal const val MAX_DIGEST_DAYS = 7

/**
 * The days one digest reports on, and the instants they run between.
 *
 * @param start the first instant of [firstDate], inclusive.
 * @param end the first instant of the day after [lastDate], exclusive.
 */
internal data class DigestPeriod(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val start: Instant,
    val end: Instant,
) {
    val isSingleDay: Boolean get() = firstDate == lastDate
}

/**
 * The last day a digest is due for at [now]: yesterday once today's digest time has come, the day before until then.
 */
internal fun lastDueDay(now: Instant, timeZone: TimeZone, time: SpamDigestTime): LocalDate {
    val today = now.toLocalDateTime(timeZone).date
    val todayAt = LocalDateTime(today, LocalTime(time.hour, time.minute)).toInstant(timeZone)

    val daysBack = if (now >= todayAt - EARLY_RUN_TOLERANCE) 1 else 2
    return today.minus(daysBack, DateTimeUnit.DAY)
}

/**
 * The days the next digest should report on: every day since the last one sent, up to the last day due, but no more
 * than [MAX_DIGEST_DAYS]; or `null` when there is nothing due.
 *
 * A digest that did not go out - its alarm never went off, or every try failed - is not lost: the next one covers
 * its day as well.
 *
 * @param lastSentDay the last day a digest reported on, or `null` before the first one.
 */
internal fun reportingPeriod(
    now: Instant,
    timeZone: TimeZone,
    time: SpamDigestTime,
    lastSentDay: LocalDate?,
): DigestPeriod? {
    val lastDate = lastDueDay(now, timeZone, time)
    if (lastSentDay != null && lastSentDay >= lastDate) return null

    val earliest = lastDate.minus(MAX_DIGEST_DAYS - 1, DateTimeUnit.DAY)
    val firstDate = lastSentDay?.plus(1, DateTimeUnit.DAY)?.coerceAtLeast(earliest) ?: lastDate

    return DigestPeriod(
        firstDate = firstDate,
        lastDate = lastDate,
        start = firstDate.atStartOfDayIn(timeZone),
        end = lastDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone),
    )
}

/**
 * Whether a digest is overdue: one has been sent before, and a day since has passed its digest time unreported -
 * the alarm never went off, the phone was off, or every try failed.
 *
 * Nothing is overdue before the first digest, so turning the digest on does not send one straight away.
 */
internal fun isDigestOverdue(now: Instant, timeZone: TimeZone, time: SpamDigestTime, lastSentDay: LocalDate?): Boolean {
    return lastSentDay != null && reportingPeriod(now, timeZone, time, lastSentDay) != null
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
