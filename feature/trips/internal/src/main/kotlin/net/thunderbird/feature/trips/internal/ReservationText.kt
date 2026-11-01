package net.thunderbird.feature.trips.internal

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Subjects that confirm a booking, rather than advertise one.
 */
private val BOOKING_SUBJECT = Regex(
    "(?i)\\b(?:confirm\\w*|reservation|reserved|booking|booked|itinerary|e-?tickets?|tickets?|your (?:trip|stay|" +
        "flight|cruise|rental|table)|check-?in|boarding pass)\\b",
)

/**
 * Subjects of offers, and of mail that follows a booking already over: a request to review the stay names it, and its
 * date, as a confirmation would.
 */
private val NOT_A_BOOKING = Regex(
    "(?i)\\b(?:sale|deal|offer|% off|newsletter|unsubscribe|win |sweepstakes|review|feedback|survey|" +
        "rate (?:your|us)|how was|how did we do|tell us|share your|" +
        "thanks? (?:you )?for (?:staying|flying|sailing|dining|visiting)|folio)\\b",
)

/**
 * The kind of booking a subject (or sender) names, in the order they are tried: a cruise or flight mentions a hotel
 * more often than the other way round, and a car rental is often a "reservation" too.
 */
private val TYPE_WORDS = listOf(
    ReservationType.CRUISE to Regex("(?i)\\b(?:cruise|sailing|voyage|embark\\w*)\\b"),
    ReservationType.FLIGHT to Regex("(?i)\\b(?:flight|airlines?|airways|boarding pass|e-?ticket|itinerary)\\b"),
    ReservationType.CAR_RENTAL to Regex(
        "(?i)\\b(?:car rental|rental car|rent a car|hertz|avis|enterprise|budget|alamo|sixt|thrifty|national car)\\b",
    ),
    ReservationType.LODGING to Regex(
        "(?i)\\b(?:hotel|stay|resort|inn|suites|lodge|airbnb|vrbo|booking\\.com|accommodation|check-?in)\\b",
    ),
    ReservationType.RESTAURANT to Regex("(?i)\\b(?:table|dinner|lunch|brunch|restaurant|opentable|resy|tock)\\b"),
    ReservationType.EVENT to Regex(
        "(?i)\\b(?:tickets?|event|concert|show|game|admission|ticketmaster|eventbrite|seats?)\\b",
    ),
)

private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|" +
    "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

/** `Nov 12, 2026`, `November 12th`, `Thu, Nov 12`. */
private val MONTH_DAY = Regex("(?i)\\b($MONTHS)\\.? (\\d{1,2})(?:st|nd|rd|th)?(?:,? (\\d{4}))?\\b")

/** `12 November 2026`, `12 Nov`. */
private val DAY_MONTH = Regex("(?i)\\b(\\d{1,2}) ($MONTHS)\\.?(?:,? (\\d{4}))?\\b")

/** `2026-11-12`. */
private val ISO_DATE = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")

/** `11/12/2026`, read month first, as in the US. */
private val US_DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})\\b")

/** `8:05 PM`, `20:05`, `8 PM`, close after a date. */
private val TIME =
    Regex("(?i)^[^\\d\\n]{0,20}?(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?m\\.?|^[^\\d\\n]{0,20}?(\\d{1,2}):(\\d{2})")

private val CONFIRMATION = Regex(
    "(?i)\\b(?:confirmation|booking|reservation|record locator|pnr)\\s*(?:number|code|no\\.?|#|id)?\\s*[:#]?\\s*" +
        "([A-Z0-9][A-Z0-9-]{4,15})\\b",
)

private val REPLY_PREFIX = Regex("(?i)^(?:(?:re|fwd?|fw)\\s*:\\s*)+")

/**
 * How much of a mail's text is read for dates: the details come first, the small print after.
 */
private const val MAX_TEXT_LENGTH = 6000

/**
 * How far ahead of the mail a booking's dates may lie and still be taken; further is more likely a policy date.
 */
private const val MAX_DAYS_AHEAD = 400L

/**
 * How long a stay, rental or cruise may run, for the second date to be its end.
 */
private const val MAX_DURATION_DAYS = 60L

private const val NOON_HOUR = 12
private const val CENTURY = 2000
private const val TWO_DIGIT_YEAR = 100
private const val PM_OFFSET = 12

/**
 * Reads a booking from a confirmation's words, for the many that carry no booking markup: what kind it is from the
 * subject, when from the first dates in the text that fall after the mail was sent, and the confirmation number.
 *
 * Words are less certain than markup, so a booking is only read from a mail whose subject both confirms a booking
 * and names its kind, and the result is marked as read from text.
 *
 * @return the booking, or `null` when the mail does not look like a confirmation or names no date.
 */
@Suppress("ReturnCount")
fun reservationFromText(
    subject: String?,
    senderName: String?,
    text: String,
    mailKey: String,
    mailDate: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Reservation? {
    val cleanSubject = subject?.replace(REPLY_PREFIX, "")?.trim() ?: return null
    if (!BOOKING_SUBJECT.containsMatchIn(cleanSubject) || NOT_A_BOOKING.containsMatchIn(cleanSubject)) return null

    val naming = "$cleanSubject ${senderName.orEmpty()}"
    val type = TYPE_WORDS.firstOrNull { (_, words) -> words.containsMatchIn(naming) }?.first ?: return null

    val sentOn = Instant.ofEpochMilli(mailDate).atZone(zone).toLocalDate()
    val times = findTimes(text.take(MAX_TEXT_LENGTH), sentOn)
    val start = times.firstOrNull() ?: return null
    val end = if (type in MULTI_DAY_TYPES) {
        times.firstOrNull { it.date > start.date && it.date <= start.date.plusDays(MAX_DURATION_DAYS) }
    } else {
        null
    }

    return Reservation(
        type = type,
        title = cleanSubject,
        start = start,
        end = end,
        confirmation = CONFIRMATION.find(text)?.groupValues?.get(1)?.takeIf(::looksLikeCode),
        isFromText = true,
        mailKey = mailKey,
        mailDate = mailDate,
    )
}

private val MULTI_DAY_TYPES = setOf(ReservationType.LODGING, ReservationType.CAR_RENTAL, ReservationType.CRUISE)

/**
 * A code has a digit in it, or is a six-letter record locator; anything else is a word that followed "confirmation".
 */
private fun looksLikeCode(value: String): Boolean =
    value.any(Char::isDigit) || (value.length == RECORD_LOCATOR_LENGTH && value.all(Char::isUpperCase))

private const val RECORD_LOCATOR_LENGTH = 6

/**
 * The dates in [text] on or after [sentOn] and not too far beyond it, in the order they appear, each with the time
 * that follows it when there is one.
 */
internal fun findTimes(text: String, sentOn: LocalDate): List<TripTime> {
    val found = mutableListOf<Pair<Int, TripTime>>()

    fun add(position: Int, endPosition: Int, date: LocalDate?) {
        if (date == null || date < sentOn || date > sentOn.plusDays(MAX_DAYS_AHEAD)) return
        val time = TIME.find(text.substring(endPosition, minOf(text.length, endPosition + TIME_LOOKAHEAD)))
            ?.let(::timeOf)
        found += position to TripTime(date.atTime(time ?: LocalTime.MIDNIGHT), hasTime = time != null)
    }

    MONTH_DAY.findAll(text).forEach { match ->
        val (month, day, year) = match.destructured
        add(match.range.first, match.range.last + 1, dateOf(year, monthOf(month), day.toInt(), sentOn))
    }
    DAY_MONTH.findAll(text).forEach { match ->
        val (day, month, year) = match.destructured
        add(match.range.first, match.range.last + 1, dateOf(year, monthOf(month), day.toInt(), sentOn))
    }
    ISO_DATE.findAll(text).forEach { match ->
        val (year, month, day) = match.destructured
        add(match.range.first, match.range.last + 1, dateOf(year, month.toInt(), day.toInt(), sentOn))
    }
    US_DATE.findAll(text).forEach { match ->
        val (month, day, year) = match.destructured
        add(match.range.first, match.range.last + 1, dateOf(year, month.toInt(), day.toInt(), sentOn))
    }

    return found.sortedBy { it.first }.map { it.second }.distinct()
}

private const val TIME_LOOKAHEAD = 30

private fun monthOf(name: String): Int = MONTH_PREFIXES.indexOf(name.take(MONTH_PREFIX_LENGTH).lowercase()) + 1

private val MONTH_PREFIXES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private const val MONTH_PREFIX_LENGTH = 3

/**
 * How long before the mail a date without a year may lie and still be read as that date, already past, rather than
 * as the same day next year: a mail about a stay that just ended names its days.
 */
private const val RECENT_PAST_DAYS = 60L

/**
 * A date without a year is the next one after the mail was sent, as a confirmation is for something to come, unless
 * it lies only shortly before the mail, when it is that day, already past.
 */
private fun dateOf(year: String, month: Int, day: Int, sentOn: LocalDate): LocalDate? = try {
    when {
        year.isEmpty() -> LocalDate.of(sentOn.year, month, day).let {
            if (it < sentOn.minusDays(RECENT_PAST_DAYS)) it.plusYears(1) else it
        }

        year.length == 2 -> LocalDate.of(CENTURY + year.toInt() % TWO_DIGIT_YEAR, month, day)

        else -> LocalDate.of(year.toInt(), month, day)
    }
} catch (_: DateTimeException) {
    null
}

@Suppress("MagicNumber")
private fun timeOf(match: MatchResult): LocalTime? {
    val groups = match.groupValues
    return try {
        if (groups[3].isNotEmpty()) {
            val hour = groups[1].toInt() % NOON_HOUR + if (groups[3].equals("p", ignoreCase = true)) PM_OFFSET else 0
            LocalTime.of(hour, groups[2].ifEmpty { "0" }.toInt())
        } else {
            LocalTime.of(groups[4].toInt(), groups[5].toInt())
        }
    } catch (_: DateTimeException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}
