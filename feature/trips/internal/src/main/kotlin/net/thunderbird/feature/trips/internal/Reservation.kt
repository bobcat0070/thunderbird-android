package net.thunderbird.feature.trips.internal

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class ReservationType {
    FLIGHT,
    LODGING,
    CAR_RENTAL,
    RESTAURANT,
    EVENT,
    CRUISE,
}

/**
 * A moment as a booking states it: the local time at the place it happens, with the offset from UTC when the
 * booking gives one.
 *
 * @param hasTime whether a time of day was given, or only a date - a hotel's check-in day, say.
 */
data class TripTime(
    val local: LocalDateTime,
    val offset: ZoneOffset? = null,
    val hasTime: Boolean = true,
) {
    val date: LocalDate
        get() = local.toLocalDate()

    /**
     * The moment as milliseconds since the epoch. Without an offset the time is taken as the device's, which is
     * right for most bookings near home and off by the time difference for some abroad.
     */
    fun epochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        (offset?.let { local.toInstant(it) } ?: local.atZone(zone).toInstant()).toEpochMilli()
}

/**
 * A booking read from a mail.
 *
 * @param title what the booking is, as the reader would name it: "United UA 1234", "Hotel Monaco".
 * @param details more about it on one line: "SFO → DEN", "Party of 4".
 * @param location where it starts, to show and to put in a calendar.
 * @param city the town it takes the reader to, for naming the trip.
 * @param confirmation the booking's confirmation or record locator.
 * @param isCancelled whether the mail cancels the booking rather than making it.
 * @param isFromText whether it was read from the mail's words rather than the booking markup senders add for mail
 *   apps; those details deserve a second look.
 * @param mailKey the mail it was read from.
 * @param mailDate when that mail was sent, so a newer mail about the same booking wins.
 */
data class Reservation(
    val type: ReservationType,
    val title: String,
    val start: TripTime,
    val end: TripTime? = null,
    val details: String? = null,
    val location: String? = null,
    val city: String? = null,
    val confirmation: String? = null,
    val isCancelled: Boolean = false,
    val isFromText: Boolean = false,
    val mailKey: String,
    val mailDate: Long,
) {
    val lastDate: LocalDate
        get() = end?.date ?: start.date
}
