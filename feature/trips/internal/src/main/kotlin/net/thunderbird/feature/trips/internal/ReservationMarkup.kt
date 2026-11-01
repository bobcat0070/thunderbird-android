@file:Suppress("TooManyFunctions")

package net.thunderbird.feature.trips.internal

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Booking markup, as airlines, hotels, rental companies, restaurants and ticket sellers put it in their
 * confirmations for mail apps to read: schema.org reservations, written as JSON-LD in a script element or as
 * microdata on the message's own elements.
 *
 * @return the bookings the markup describes, or none when the mail has no markup.
 */
fun reservationsFromMarkup(html: String, mailKey: String, mailDate: Long): List<Reservation> {
    val document = Jsoup.parse(html)
    val items = document.select("script[type=application/ld+json]").flatMap { script -> jsonLdItems(script.data()) } +
        document.select("[itemscope][itemtype]:not([itemprop])").map(::microdataItem)

    return items.flatMap(::flatten).mapNotNull { item -> reservationOf(item, mailKey, mailDate) }
}

private val lenientJson = Json { isLenient = true }

private fun jsonLdItems(data: String): List<JsonElement> = try {
    listOf(lenientJson.parseToJsonElement(data))
} catch (_: SerializationException) {
    emptyList()
} catch (_: IllegalArgumentException) {
    emptyList()
}

/**
 * A script may hold one item, a list of them, or a graph.
 */
private fun flatten(element: JsonElement): List<JsonObject> = when (element) {
    is JsonArray -> element.flatMap(::flatten)
    is JsonObject -> (element["@graph"] as? JsonArray)?.flatMap(::flatten) ?: listOf(element)
    else -> emptyList()
}

/**
 * Microdata as the same shape JSON-LD has: each `itemprop` a key, each nested `itemscope` an object.
 */
private fun microdataItem(element: Element): JsonObject {
    val properties = linkedMapOf<String, JsonElement>("@type" to JsonPrimitive(element.attr("itemtype")))

    fun collect(parent: Element) {
        for (child in parent.children()) {
            val names = child.attr("itemprop").split(' ').filter { it.isNotBlank() }
            if (names.isNotEmpty()) {
                val value = if (child.hasAttr(
                        "itemscope",
                    )
                ) {
                    microdataItem(child)
                } else {
                    JsonPrimitive(microdataValue(child))
                }
                names.forEach { name -> properties.putIfAbsent(name, value) }
            }
            if (!child.hasAttr("itemscope")) collect(child)
        }
    }

    collect(element)
    return JsonObject(properties)
}

private fun microdataValue(element: Element): String = when {
    element.hasAttr("content") -> element.attr("content")
    element.hasAttr("datetime") -> element.attr("datetime")
    element.tagName() == "a" || element.tagName() == "link" -> element.attr("href")
    else -> element.text()
}

private fun JsonObject.types(): List<String> {
    val type = this["@type"] ?: return emptyList()
    val names = if (type is JsonArray) type.mapNotNull { (it as? JsonPrimitive)?.content } else listOf(type.text())
    return names.filterNotNull().map { it.substringAfterLast('/') }
}

private fun JsonElement?.text(): String? = when (this) {
    is JsonPrimitive -> content.takeIf { isString || it != "null" }?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject -> this["name"].text()
    is JsonArray -> firstOrNull().text()
    else -> null
}

private fun JsonObject.obj(name: String): JsonObject? = when (val value = this[name]) {
    is JsonObject -> value
    is JsonArray -> value.firstOrNull() as? JsonObject
    else -> null
}

private fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { this[it].text() }

private fun JsonObject.time(vararg names: String): TripTime? = names.firstNotNullOfOrNull {
    this[it].text()?.let(::parseTime)
}

private val TYPE_BY_SCHEMA = mapOf(
    "FlightReservation" to ReservationType.FLIGHT,
    "LodgingReservation" to ReservationType.LODGING,
    "RentalCarReservation" to ReservationType.CAR_RENTAL,
    "FoodEstablishmentReservation" to ReservationType.RESTAURANT,
    "EventReservation" to ReservationType.EVENT,
    "BoatReservation" to ReservationType.CRUISE,
)

private fun reservationOf(item: JsonObject, mailKey: String, mailDate: Long): Reservation? {
    val types = item.types()
    val type = types.firstNotNullOfOrNull { TYPE_BY_SCHEMA[it] }
        ?: ReservationType.CRUISE.takeIf { types.any { it.contains("Cruise", ignoreCase = true) } }
        ?: return null
    val subject = item.obj("reservationFor") ?: JsonObject(emptyMap())
    val base = MarkupBase(
        confirmation = item.text("reservationNumber", "confirmationNumber"),
        isCancelled = item.text("reservationStatus")?.contains("Cancel", ignoreCase = true) == true,
        mailKey = mailKey,
        mailDate = mailDate,
    )

    return when (type) {
        ReservationType.FLIGHT -> flight(item, subject, base)
        ReservationType.LODGING -> lodging(item, subject, base)
        ReservationType.CAR_RENTAL -> carRental(item, subject, base)
        ReservationType.RESTAURANT -> restaurant(item, subject, base)
        ReservationType.EVENT -> event(item, subject, base)
        ReservationType.CRUISE -> cruise(item, subject, base)
    }
}

private class MarkupBase(
    val confirmation: String?,
    val isCancelled: Boolean,
    val mailKey: String,
    val mailDate: Long,
)

private fun MarkupBase.reservation(
    type: ReservationType,
    title: String,
    start: TripTime,
    end: TripTime?,
    details: String?,
    location: String?,
    city: String?,
) = Reservation(
    type = type,
    title = title,
    start = start,
    end = end,
    details = details,
    location = location,
    city = city,
    confirmation = confirmation,
    isCancelled = isCancelled,
    mailKey = mailKey,
    mailDate = mailDate,
)

private fun flight(item: JsonObject, flight: JsonObject, base: MarkupBase): Reservation? {
    val departure = flight.obj("departureAirport")
    val arrival = flight.obj("arrivalAirport")
    val start = flight.time("departureTime") ?: item.time("departureTime", "startTime") ?: return null
    val airline = flight.obj("airline")
    val airlineCode = airline?.text("iataCode")
    val number = flight.text("flightNumber")?.let { number ->
        if (airlineCode != null && !number.startsWith(airlineCode)) "$airlineCode $number" else number
    }
    val title = listOfNotNull(airline?.text("name"), number).joinToString(" ").ifEmpty { "Flight" }
    val from = departure?.text("iataCode") ?: departure?.text("name")
    val to = arrival?.text("iataCode") ?: arrival?.text("name")

    return base.reservation(
        type = ReservationType.FLIGHT,
        title = title,
        start = start,
        end = flight.time("arrivalTime"),
        details = if (from != null && to != null) "$from → $to" else null,
        location = departure?.text("name") ?: from,
        city = arrival?.obj("address")?.text("addressLocality") ?: arrival?.text("name") ?: to,
    )
}

private fun lodging(item: JsonObject, place: JsonObject, base: MarkupBase): Reservation? {
    val start = item.time("checkinTime", "checkinDate", "checkInTime", "checkInDate") ?: return null
    return base.reservation(
        type = ReservationType.LODGING,
        title = place.text("name") ?: "Stay",
        start = start,
        end = item.time("checkoutTime", "checkoutDate", "checkOutTime", "checkOutDate"),
        details = null,
        location = address(place),
        city = place.obj("address")?.text("addressLocality"),
    )
}

private fun carRental(item: JsonObject, car: JsonObject, base: MarkupBase): Reservation? {
    val start = item.time("pickupTime") ?: return null
    val pickup = item.obj("pickupLocation")
    val company = car.obj("rentalCompany")?.text("name") ?: item.obj("provider")?.text("name") ?: car.text("brand")
    return base.reservation(
        type = ReservationType.CAR_RENTAL,
        title = company?.let { "$it car rental" } ?: "Car rental",
        start = start,
        end = item.time("dropoffTime"),
        details = listOfNotNull(car.text("brand"), car.text("model")).joinToString(" ").ifEmpty { null },
        location = pickup?.let { listOfNotNull(it.text("name"), address(it)).distinct().joinToString(", ") },
        city = pickup?.obj("address")?.text("addressLocality"),
    )
}

private fun restaurant(item: JsonObject, place: JsonObject, base: MarkupBase): Reservation? {
    val start = item.time("startTime", "startDate") ?: return null
    val partySize = item.text("partySize")
    return base.reservation(
        type = ReservationType.RESTAURANT,
        title = place.text("name") ?: "Restaurant",
        start = start,
        end = item.time("endTime"),
        details = partySize?.let { "Party of $it" },
        location = address(place),
        city = place.obj("address")?.text("addressLocality"),
    )
}

private fun event(item: JsonObject, event: JsonObject, base: MarkupBase): Reservation? {
    val start = event.time("startDate", "startTime") ?: item.time("startTime", "startDate") ?: return null
    val venue = event.obj("location")
    return base.reservation(
        type = ReservationType.EVENT,
        title = event.text("name") ?: "Event",
        start = start,
        end = event.time("endDate", "endTime"),
        details = venue?.text("name"),
        location = venue?.let { listOfNotNull(it.text("name"), address(it)).distinct().joinToString(", ") },
        city = venue?.obj("address")?.text("addressLocality"),
    )
}

private fun cruise(item: JsonObject, trip: JsonObject, base: MarkupBase): Reservation? {
    val start = trip.time("departureTime") ?: item.time("startTime", "departureTime") ?: return null
    val departure = trip.obj("departureBoatTerminal")
    val arrival = trip.obj("arrivalBoatTerminal")
    val line = trip.obj("provider")?.text("name") ?: item.obj("provider")?.text("name")
    return base.reservation(
        type = ReservationType.CRUISE,
        title = trip.text("name") ?: line ?: "Cruise",
        start = start,
        end = trip.time("arrivalTime") ?: item.time("endTime"),
        details = line?.takeIf { it != trip.text("name") },
        location = departure?.let { listOfNotNull(it.text("name"), address(it)).distinct().joinToString(", ") },
        city = departure?.obj("address")?.text("addressLocality") ?: departure?.text("name"),
    )
}

@Suppress("ReturnCount")
private fun address(place: JsonObject): String? {
    val address = place["address"] ?: return null
    if (address !is JsonObject) return address.text()
    return listOfNotNull(
        address.text("streetAddress"),
        address.text("addressLocality"),
        address.text("addressRegion"),
        address.text("postalCode"),
    ).joinToString(", ").ifEmpty { null }
}

/**
 * Reads the ways booking markup writes a moment: `2026-11-12T08:05:00-07:00`, without the offset, with a space for
 * the `T`, or a date alone.
 */
internal fun parseTime(value: String): TripTime? {
    val text = value.trim().replace(' ', 'T')
    return try {
        val dateTime = OffsetDateTime.parse(text)
        TripTime(dateTime.toLocalDateTime(), dateTime.offset)
    } catch (_: DateTimeParseException) {
        try {
            TripTime(LocalDateTime.parse(text))
        } catch (_: DateTimeParseException) {
            try {
                TripTime(LocalDate.parse(text.take(ISO_DATE_LENGTH)).atStartOfDay(), hasTime = false)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

private const val ISO_DATE_LENGTH = 10
