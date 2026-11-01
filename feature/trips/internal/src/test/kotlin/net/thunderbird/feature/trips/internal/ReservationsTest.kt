package net.thunderbird.feature.trips.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.single
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test

private val MAIL_DATE = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

class ReservationsTest {
    @Test
    fun `a JSON-LD flight should be read with its route, times and confirmation`() {
        val html = """
            <html><head><script type="application/ld+json">
            {"@context":"http://schema.org","@type":"FlightReservation","reservationNumber":"RXJ34P",
             "reservationFor":{"@type":"Flight","flightNumber":"1234",
               "airline":{"@type":"Airline","name":"United","iataCode":"UA"},
               "departureAirport":{"@type":"Airport","name":"San Francisco Airport","iataCode":"SFO"},
               "departureTime":"2026-11-12T08:05:00-08:00",
               "arrivalAirport":{"@type":"Airport","name":"Denver International","iataCode":"DEN",
                 "address":{"addressLocality":"Denver"}},
               "arrivalTime":"2026-11-12T11:20:00-07:00"}}
            </script></head><body>Your trip</body></html>
        """.trimIndent()

        val reservation = reservationsFromMarkup(html, "mail1", MAIL_DATE).single()

        assertThat(reservation.type).isEqualTo(ReservationType.FLIGHT)
        assertThat(reservation.title).isEqualTo("United UA 1234")
        assertThat(reservation.details).isEqualTo("SFO → DEN")
        assertThat(reservation.city).isEqualTo("Denver")
        assertThat(reservation.confirmation).isEqualTo("RXJ34P")
        assertThat(reservation.start).isEqualTo(
            TripTime(LocalDateTime.of(2026, 11, 12, 8, 5), ZoneOffset.ofHours(-8)),
        )
    }

    @Test
    fun `microdata lodging should be read with its days and address`() {
        val html = """
            <div itemscope itemtype="http://schema.org/LodgingReservation">
              <meta itemprop="reservationNumber" content="H-55821"/>
              <div itemprop="reservationFor" itemscope itemtype="http://schema.org/LodgingBusiness">
                <span itemprop="name">Hotel Monaco</span>
                <div itemprop="address" itemscope itemtype="http://schema.org/PostalAddress">
                  <span itemprop="streetAddress">1717 Champa St</span>
                  <span itemprop="addressLocality">Denver</span>
                </div>
              </div>
              <time itemprop="checkinDate" datetime="2026-11-12"></time>
              <time itemprop="checkoutDate" datetime="2026-11-15"></time>
            </div>
        """.trimIndent()

        val reservation = reservationsFromMarkup(html, "mail2", MAIL_DATE).single()

        assertThat(reservation.type).isEqualTo(ReservationType.LODGING)
        assertThat(reservation.title).isEqualTo("Hotel Monaco")
        assertThat(reservation.location).isEqualTo("1717 Champa St, Denver")
        assertThat(reservation.start.hasTime).isEqualTo(false)
        assertThat(reservation.end?.date).isEqualTo(LocalDate.of(2026, 11, 15))
    }

    @Test
    fun `restaurant, event, car rental and cruise markup should each be read`() {
        val html = """
            <script type="application/ld+json">
            [{"@type":"FoodEstablishmentReservation","partySize":"4","startTime":"2026-11-13T19:30:00",
              "reservationFor":{"@type":"FoodEstablishment","name":"Root Down"}},
             {"@type":"EventReservation","reservationFor":{"@type":"Event","name":"Nuggets vs Lakers",
              "startDate":"2026-11-14T19:00:00","location":{"@type":"Place","name":"Ball Arena"}}},
             {"@type":"RentalCarReservation","pickupTime":"2026-11-12T12:00:00","dropoffTime":"2026-11-15T09:00:00",
              "reservationFor":{"@type":"RentalCar","rentalCompany":{"name":"Hertz"}},
              "pickupLocation":{"name":"DEN Airport"}},
             {"@type":"BoatReservation","reservationFor":{"@type":"BoatTrip","name":"Alaska Inside Passage",
              "departureTime":"2027-06-01","arrivalTime":"2027-06-08",
              "departureBoatTerminal":{"name":"Seattle Pier 91"}}}]
            </script>
        """.trimIndent()

        val reservations = reservationsFromMarkup(html, "mail3", MAIL_DATE)

        assertThat(reservations.map { it.type }).containsExactly(
            ReservationType.RESTAURANT,
            ReservationType.EVENT,
            ReservationType.CAR_RENTAL,
            ReservationType.CRUISE,
        )
        assertThat(reservations[0].details).isEqualTo("Party of 4")
        assertThat(reservations[2].title).isEqualTo("Hertz car rental")
        assertThat(reservations[3].title).isEqualTo("Alaska Inside Passage")
    }

    @Test
    fun `a confirmation without markup should be read from its words`() {
        val text = """
            Thanks for booking with us!
            Confirmation number: 8836102
            Check-in: Thursday, November 12, 2026 at 3:00 PM
            Check-out: Sunday, November 15, 2026 at 11:00 AM
        """.trimIndent()

        val reservation = reservationFromText(
            subject = "Your stay at The Maven is confirmed",
            senderName = "The Maven Hotel",
            text = text,
            mailKey = "mail4",
            mailDate = MAIL_DATE,
            zone = ZoneId.of("UTC"),
        )

        assertThat(reservation).isNotNull()
        assertThat(reservation!!.type).isEqualTo(ReservationType.LODGING)
        assertThat(reservation.isFromText).isTrue()
        assertThat(reservation.confirmation).isEqualTo("8836102")
        assertThat(reservation.start.local).isEqualTo(LocalDateTime.of(2026, 11, 12, 15, 0))
        assertThat(reservation.end?.local).isEqualTo(LocalDateTime.of(2026, 11, 15, 11, 0))
    }

    @Test
    fun `a promotion should not be read as a booking`() {
        val reservation = reservationFromText(
            subject = "Book your next flight: 20% off sale",
            senderName = "Airline",
            text = "Fly by November 30, 2026",
            mailKey = "mail5",
            mailDate = MAIL_DATE,
        )

        assertThat(reservation).isNull()
    }

    @Test
    fun `bookings close in time should form one trip named for the flight's destination`() {
        val flight = reservation(ReservationType.FLIGHT, "UA 1234", day(12), city = "Denver")
        val hotel = reservation(ReservationType.LODGING, "Hotel Monaco", day(12), end = day(15), city = "Denver")
        val flightHome = reservation(ReservationType.FLIGHT, "UA 4321", day(16), city = "San Francisco")
        val dinnerLater = reservation(ReservationType.RESTAURANT, "Zuni", day(28))

        val trips = groupTrips(listOf(dinnerLater, flightHome, hotel, flight), today = LocalDate.of(2026, 11, 1))

        assertThat(trips.map { it.place }).containsExactly("Denver", null)
        assertThat(trips[0].reservations.map { it.title }).containsExactly("UA 1234", "Hotel Monaco", "UA 4321")
        assertThat(trips[0].lastDate).isEqualTo(LocalDate.of(2026, 11, 16))
    }

    @Test
    fun `repeated, cancelled and past bookings should be dropped`() {
        val booked = reservation(ReservationType.LODGING, "Inn", day(12), confirmation = "A1", mailDate = 1)
        val reminder = reservation(ReservationType.LODGING, "Inn", day(12), confirmation = "A1", mailDate = 2)
        val cancelled = reservation(ReservationType.FLIGHT, "UA 9", day(20), confirmation = "B2")
            .copy(isCancelled = true)
        val cancelledBooking = reservation(ReservationType.FLIGHT, "UA 9", day(20), confirmation = "B2")
        val past = reservation(ReservationType.RESTAURANT, "Old dinner", day(1))

        val trips = groupTrips(
            listOf(booked, reminder, cancelled, cancelledBooking, past),
            today = LocalDate.of(2026, 11, 5),
        )

        assertThat(trips).single().transform { it.reservations }.single().transform { it.mailDate }.isEqualTo(2)
    }

    @Test
    fun `a stay with only dates should become an all-day calendar event over its days`() {
        val hotel = reservation(ReservationType.LODGING, "Hotel Monaco", day(12), end = day(15))
            .copy(confirmation = "H1", location = "Denver")

        val event = calendarEventOf(hotel, "Confirmation:")

        assertThat(event.isAllDay).isTrue()
        assertThat(event.begin).isEqualTo(utcMidnight(12))
        assertThat(event.end).isEqualTo(utcMidnight(16))
        assertThat(event.description).isEqualTo("Confirmation: H1")
        assertThat(event.location).isEqualTo("Denver")
    }

    @Test
    fun `a flight should become a timed event named for its route`() {
        val flight = Reservation(
            type = ReservationType.FLIGHT,
            title = "United UA 1234",
            start = TripTime(LocalDateTime.of(2026, 11, 12, 8, 5), ZoneOffset.ofHours(-8)),
            end = TripTime(LocalDateTime.of(2026, 11, 12, 11, 20), ZoneOffset.ofHours(-7)),
            details = "SFO → DEN",
            mailKey = "m",
            mailDate = 0,
        )

        val event = calendarEventOf(flight, "Confirmation:")

        assertThat(event.title).isEqualTo("United UA 1234 SFO → DEN")
        assertThat(event.isAllDay).isEqualTo(false)
        assertThat(event.end - event.begin).isEqualTo(135L * 60 * 1000)
    }

    private fun day(day: Int) = TripTime(LocalDate.of(2026, 11, day).atStartOfDay(), hasTime = false)

    private fun utcMidnight(day: Int) = LocalDate.of(
        2026,
        11,
        day,
    ).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Suppress("LongParameterList")
    private fun reservation(
        type: ReservationType,
        title: String,
        start: TripTime,
        end: TripTime? = null,
        city: String? = null,
        confirmation: String? = null,
        mailDate: Long = 0,
    ) = Reservation(
        type = type,
        title = title,
        start = start,
        end = end,
        city = city,
        confirmation = confirmation,
        mailKey = "mail-$title-$mailDate",
        mailDate = mailDate,
    )
}
