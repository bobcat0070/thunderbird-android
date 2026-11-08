package net.thunderbird.feature.trips.internal

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * Subjects that confirm a booking, rather than advertise one.
 */
private val BOOKING_SUBJECT = Regex(
    "(?i)\\b(?:confirm\\w*|reservation|reserved|booking|booked|itinerary|e-?tickets?|your (?:trip|stay|" +
        "flight|cruise|rental|table)|check-?in|boarding pass)\\b",
)

/**
 * Subjects about the reader's own tickets: "Your Zoo Tickets: 4417", "You Got Tickets To ...", "Ticket Order
 * Information". "Tickets" alone is what a seller's offers and listings say too - "$30 Tickets", "Tickets to ... and
 * more" - and a support desk's "Ticket #5521".
 */
private val OWN_TICKETS_SUBJECT = Regex(
    "(?i)\\b(?:(?:your|you(?:'ve| have)? got|here are|digital|mobile|print-at-home) (?:\\S+ ){0,4}?tickets?|" +
        "tickets? (?:order|confirm\\w*|purchase|receipt))\\b",
)

/**
 * Subjects that say something about a trip, which is enough from a sender on the travel sender list: such a sender's
 * reminders, receipts and welcome mail name the booking's dates.
 */
private val TRIP_SUBJECT = Regex(
    "(?i)\\b(?:order|receipt|reminder|upcoming|welcome|trip|visit|vacation|documents?|countdown|get ready|" +
        "your (?:room|cabin|car|table))\\b",
)

/**
 * Subjects of offers, of mail that follows a booking already over - a request to review the stay names it, and its
 * date, as a confirmation would - and of a support desk's tickets.
 */
private val NOT_A_BOOKING = Regex(
    "(?i)\\b(?:sale|deals?|offers?|% off|\\d+ off|discount|bonus|book now|book today|your next|" +
        "last-minute|secure your|get your tickets|don't miss|presale|pre-sale|just announced|limited time|" +
        "newsletter|unsubscribe|win |sweepstakes|review|feedback|survey|" +
        "rate (?:your|us)|how was|how did we do|tell us|share your|" +
        "thanks? (?:you )?for (?:staying|flying|sailing|dining|visiting)|folio|" +
        "ticket (?:has been |was |is )?(?:submitted|updated|closed|resolved))\\b",
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
 * What is known of a mail beyond its words.
 *
 * @param isListedSender whether its sender is on the travel sender list.
 * @param senderType the kind of booking the listed sender sends, when it sends only one.
 * @param isMarked whether the reader marked the mail as trip mail.
 */
@Serializable
data class MailHints(
    val isListedSender: Boolean = false,
    val senderType: ReservationType? = null,
    val isMarked: Boolean = false,
)

/**
 * Reads a booking from a confirmation's words, for the many that carry no booking markup: what kind it is from the
 * subject, when from the first dates in the text that fall after the mail was sent, and the confirmation number.
 *
 * Words are less certain than markup, so a booking is only read from a mail whose subject both confirms a booking
 * and names its kind, and the result is marked as read from text. A sender on the travel sender list needs a subject
 * that only says something about a trip, and its kind comes from the sender, or else the text; a mail the reader
 * marked needs neither.
 *
 * @return the booking, or `null` when the mail does not look like a confirmation or names no date.
 */
@Suppress("ReturnCount", "LongParameterList")
fun reservationFromText(
    subject: String?,
    senderName: String?,
    text: String,
    mailKey: String,
    mailDate: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    hints: MailHints = MailHints(),
): Reservation? {
    val cleanSubject = subject?.replace(REPLY_PREFIX, "")?.trim()
        ?: if (hints.isMarked) "" else return null
    if (!hints.isMarked && !isBookingSubject(cleanSubject, hints.isListedSender)) return null

    val readText = text.take(MAX_TEXT_LENGTH)
    val isTravelMail = hints.isListedSender || hints.isMarked
    val type = typeIn("$cleanSubject ${senderName.orEmpty()}")
        ?: hints.senderType
        ?: (if (isTravelMail) firstTypeIn(readText) else null)
        ?: (if (hints.isMarked) ReservationType.EVENT else null)
        ?: return null

    val sentOn = Instant.ofEpochMilli(mailDate).atZone(zone).toLocalDate()
    val times = findTimes(readText, sentOn)
    val start = times.firstOrNull() ?: return null
    val end = if (type in MULTI_DAY_TYPES) {
        times.firstOrNull { it.date > start.date && it.date <= start.date.plusDays(MAX_DURATION_DAYS) }
    } else {
        null
    }

    return Reservation(
        type = type,
        title = cleanSubject.ifEmpty { senderName.orEmpty() },
        start = start,
        end = end,
        confirmation = CONFIRMATION.find(text)?.groupValues?.get(1)?.takeIf(::looksLikeCode),
        isFromText = true,
        mailKey = mailKey,
        mailDate = mailDate,
    )
}

private fun isBookingSubject(subject: String, isListedSender: Boolean): Boolean {
    val confirms = BOOKING_SUBJECT.containsMatchIn(subject) ||
        OWN_TICKETS_SUBJECT.containsMatchIn(subject) ||
        (isListedSender && TRIP_SUBJECT.containsMatchIn(subject))
    return confirms && !NOT_A_BOOKING.containsMatchIn(subject)
}

/**
 * The kind of booking [naming] names, a subject with its sender, tried in the order of [TYPE_WORDS].
 */
private fun typeIn(naming: String): ReservationType? =
    TYPE_WORDS.firstOrNull { (_, words) -> words.containsMatchIn(naming) }?.first

/**
 * The kind of booking named first in [text]: a mail's body names what it is about before the resort it is at.
 */
private fun firstTypeIn(text: String): ReservationType? = TYPE_WORDS
    .mapNotNull { (type, words) -> words.find(text)?.let { type to it.range.first } }
    .minByOrNull { (_, position) -> position }
    ?.first

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
