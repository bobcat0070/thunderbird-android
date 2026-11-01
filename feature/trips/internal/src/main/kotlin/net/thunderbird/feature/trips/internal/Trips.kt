package net.thunderbird.feature.trips.internal

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Bookings close enough in time to be one trip.
 *
 * @param place where the trip goes, when a booking says: the flight's destination, else the stay's town.
 */
data class Trip(
    val place: String?,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val reservations: List<Reservation>,
)

/**
 * Bookings that start within this many days of the previous one ending belong to the same trip: a flight the day
 * after a hotel's checkout is the flight home.
 */
private const val SAME_TRIP_GAP_DAYS = 1L

/**
 * Keeps one copy of each booking - the newest mail about it, since a change or a reminder repeats it - drops the
 * cancelled ones, and gathers the rest that have not ended before [today] into trips, soonest first.
 */
fun groupTrips(reservations: List<Reservation>, today: LocalDate): List<Trip> {
    val cancelled = reservations.filter { it.isCancelled }.mapNotNullTo(mutableSetOf()) { it.confirmation }
    val upcoming = reservations
        .filterNot { it.isCancelled || it.confirmation in cancelled }
        .sortedByDescending { it.mailDate }
        .distinctBy { it.identity() }
        .filter { it.lastDate >= today }
        // A booking with only a date - a hotel's check-in day - comes after that day's timed ones, and on the same
        // time the flight comes first: the reader lands, then checks in.
        .sortedWith(compareBy({ it.start.sortKey() }, { it.type.ordinal }))

    val trips = mutableListOf<MutableList<Reservation>>()
    for (reservation in upcoming) {
        val current = trips.lastOrNull()
        val currentEnd = current?.maxOf { it.lastDate }
        if (current != null && currentEnd != null &&
            reservation.start.date <= currentEnd.plusDays(SAME_TRIP_GAP_DAYS)
        ) {
            current += reservation
        } else {
            trips += mutableListOf(reservation)
        }
    }

    return trips.map { bookings ->
        Trip(
            place = placeOf(bookings),
            firstDate = bookings.first().start.date,
            lastDate = bookings.maxOf { it.lastDate },
            reservations = bookings,
        )
    }
}

private fun TripTime.sortKey(): LocalDateTime = if (hasTime) local else date.atTime(LocalTime.MAX)

/**
 * The same booking, however many mails mention it: by its confirmation number when it has one, else by what it is
 * and when.
 */
private fun Reservation.identity(): String =
    listOf(type.name, confirmation ?: title, start.date.toString(), details.orEmpty()).joinToString("|")

private fun placeOf(bookings: List<Reservation>): String? =
    bookings.firstOrNull { it.type == ReservationType.FLIGHT }?.city
        ?: bookings.firstOrNull { it.type == ReservationType.LODGING }?.city
        ?: bookings.firstOrNull { it.type == ReservationType.CRUISE }?.title
        ?: bookings.firstNotNullOfOrNull { it.city }

/**
 * A calendar event for a booking, as the calendar app's "new event" screen takes it.
 *
 * @param begin and [end] are milliseconds since the epoch; for an all-day event, UTC midnight of the first day and
 *   of the day after the last, as Android's calendar expects.
 */
data class CalendarEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val isAllDay: Boolean,
    val location: String?,
    val description: String,
)

private const val HOUR_MILLIS = 60 * 60 * 1000L

/**
 * How long an event lasts when its booking gives no end: a meal, a show, a flight's boarding to landing.
 */
@Suppress("MagicNumber")
private val DEFAULT_DURATION_HOURS = mapOf(
    ReservationType.FLIGHT to 2L,
    ReservationType.RESTAURANT to 2L,
    ReservationType.EVENT to 3L,
    ReservationType.CAR_RENTAL to 1L,
    ReservationType.LODGING to 1L,
    ReservationType.CRUISE to 1L,
)

/**
 * Turns a booking into a calendar event. A booking with only dates - a hotel with check-in and check-out days, a
 * cruise - becomes an all-day event over those days; one with times keeps them.
 *
 * @param confirmationLabel how to introduce the confirmation number in the event's description.
 */
fun calendarEventOf(reservation: Reservation, confirmationLabel: String): CalendarEvent {
    val description = listOfNotNull(
        reservation.details,
        reservation.confirmation?.let { "$confirmationLabel $it" },
    ).joinToString("\n")
    val title = reservation.details
        ?.takeIf { reservation.type == ReservationType.FLIGHT }
        ?.let { "${reservation.title} $it" }
        ?: reservation.title

    if (!reservation.start.hasTime) {
        val lastDay = reservation.end?.date?.takeIf { it > reservation.start.date } ?: reservation.start.date
        return CalendarEvent(
            title = title,
            begin = reservation.start.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            end = lastDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            isAllDay = true,
            location = reservation.location,
            description = description,
        )
    }

    val begin = reservation.start.epochMillis()
    val end = reservation.end?.takeIf { it.hasTime }?.epochMillis()?.takeIf { it > begin }
        ?: (begin + DEFAULT_DURATION_HOURS.getValue(reservation.type) * HOUR_MILLIS)
    return CalendarEvent(
        title = title,
        begin = begin,
        end = end,
        isAllDay = false,
        location = reservation.location,
        description = description,
    )
}
