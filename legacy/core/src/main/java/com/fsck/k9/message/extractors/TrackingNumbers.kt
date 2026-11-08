@file:Suppress("TooManyFunctions")

package com.fsck.k9.message.extractors

import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.message.html.HtmlConverter

/**
 * A carrier whose tracking numbers can be recognised, with its own tracking page for one.
 */
enum class Carrier(val displayName: String, private val trackingPage: String) {
    UPS("UPS", "https://www.ups.com/track?tracknum="),
    USPS("USPS", "https://tools.usps.com/go/TrackConfirmAction?tLabels="),
    FEDEX("FedEx", "https://www.fedex.com/fedextrack/?trknbr="),
    DHL("DHL", "https://www.dhl.com/global-en/home/tracking/tracking-express.html?submit=1&tracking-id="),
    ;

    fun trackingUrl(number: String): String = trackingPage + number

    internal fun isTrackingUrl(url: String): Boolean =
        url.startsWith(trackingPage) && url.substring(trackingPage.length).matches(TRACKING_NUMBER_CHARACTERS)
}

private val TRACKING_NUMBER_CHARACTERS = Regex("[0-9A-Z]+")

/**
 * Whether [url] is a carrier's tracking page as [TrackingNumber.url] builds it: the carrier's own address with only a
 * tracking number after it, and so nothing of any sender's in it.
 */
fun isCarrierTrackingUrl(url: String): Boolean = Carrier.entries.any { carrier -> carrier.isTrackingUrl(url) }

/**
 * @property number the tracking number without spaces, as the carrier's page takes it.
 */
data class TrackingNumber(val carrier: Carrier, val number: String) {
    /**
     * The carrier's own tracking page for this number, with nothing of the sender's in it.
     */
    val url: String
        get() = carrier.trackingUrl(number)
}

/**
 * Words that say a mail is about a parcel. A number that passes a carrier's check is only taken from a mail with one
 * of them, since a long order or invoice number can pass a check by chance.
 */
private val SHIPPING_WORD = Regex(
    "(?iu)(?<!\\p{L})(?:track\\p{L}*|ship\\p{L}*|package|parcel|deliver\\p{L}*|carrier|usps|ups|fedex|dhl|" +
        "shipment|envío|paquete|seguimiento|paket|sendung|lieferung|посылк\\p{L}*|отправлени\\p{L}*)(?!\\p{L})",
)

/**
 * UPS: `1Z`, six characters for the shipper, two for the service and eight for the parcel, the last a check digit.
 * Often written in groups, as in `1Z 999 AA1 01 2345 6784`.
 */
private val UPS_NUMBER = Regex("""(?<![0-9A-Za-z])1Z(?: ?[0-9A-Z]){16}(?![0-9A-Za-z])""")

/**
 * USPS Intelligent Mail package barcodes: 22 digits starting `92` to `95`, often in groups of four.
 */
private val USPS_NUMBER = Regex("""(?<![0-9A-Za-z])9[2-5]\d{2}(?: ?\d{4}){4} ?\d{2}(?![0-9A-Za-z])""")

/**
 * International (UPU S10) numbers sent with USPS: two letters, eight digits, a check digit and `US`.
 */
private val USPS_INTERNATIONAL_NUMBER = Regex("""(?<![0-9A-Za-z])[A-Z]{2}\d{9}US(?![0-9A-Za-z])""")

/**
 * FedEx Express (12 digits, with a check digit) and Ground (15 digits). Ordinary numbers that long are common, so
 * these are only taken next to the word FedEx.
 */
private val FEDEX_NUMBER = Regex("""(?<![0-9A-Za-z])(?:\d{12}|\d{15})(?![0-9A-Za-z])""")
private val FEDEX_WORD = Regex("""(?i)(?<![a-z])fedex(?![a-z])""")

/**
 * DHL Express: 10 digits, the last a check digit. Only taken next to the word DHL.
 */
private val DHL_NUMBER = Regex("""(?<![0-9A-Za-z])\d{10}(?![0-9A-Za-z])""")
private val DHL_WORD = Regex("""(?i)(?<![a-z])dhl(?![a-z])""")

/**
 * How far a FedEx or DHL number may be from the carrier's name, in characters.
 */
private const val MAX_CARRIER_DISTANCE = 80

/**
 * How much of a message is read; shipping mail puts the number near the top.
 */
private const val MAX_TEXT_LENGTH = 20_000

private const val UPS_PREFIX_LENGTH = 2
private const val FEDEX_EXPRESS_LENGTH = 12
private const val DECIMAL = 10
private const val S10_MODULUS = 11
private const val DHL_MODULUS = 7
@Suppress("MagicNumber")
private val S10_WEIGHTS = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)

@Suppress("MagicNumber")
private val FEDEX_WEIGHTS = intArrayOf(1, 3, 7)
private const val USPS_ODD_WEIGHT = 3
private const val UPS_LETTER_OFFSET = 63

/**
 * Finds the first parcel tracking number in a shipping mail - UPS, USPS, FedEx or DHL - so it can be looked up on
 * the carrier's own page, rather than through the sender's link, which usually goes through a click tracker.
 *
 * Reads [subject] and the message's text; a number is only taken from a mail that says something about shipping,
 * and only when it passes the carrier's check digit. FedEx and DHL numbers look like any other number, so they are
 * also only taken next to the carrier's name.
 *
 * @return the number, or `null` when the mail has none.
 */
fun findTrackingNumber(message: Part, subject: String?): TrackingNumber? =
    findTrackingNumbers(message, subject).firstOrNull()

/**
 * Finds every parcel tracking number in a shipping mail, in the order they appear, each once; an order that ships in
 * several parcels names a number for each. The same checks apply as for [findTrackingNumber].
 */
fun findTrackingNumbers(message: Part, subject: String?): List<TrackingNumber> {
    val text = buildString {
        subject?.let { append(it).append('\n') }
        messagePlainText(message)?.let { append(it) }
    }
    return findTrackingNumbersInText(text)
}

/**
 * The message's text as a reader sees it: its plain-text part, or its HTML part turned into text.
 *
 * @return the text, or `null` when the message has neither, or its body has not been downloaded.
 */
fun messagePlainText(message: Part): String? {
    MimeUtility.findFirstPartByMimeType(message, "text/plain")
        ?.let(MessageExtractor::getTextFromPart)
        ?.let { return it }

    return MimeUtility.findFirstPartByMimeType(message, "text/html")
        ?.let(MessageExtractor::getTextFromPart)
        ?.let(HtmlConverter::htmlToText)
}

internal fun findTrackingNumberInText(fullText: String): TrackingNumber? =
    findTrackingNumbersInText(fullText).firstOrNull()

fun findTrackingNumbersInText(fullText: String): List<TrackingNumber> {
    val text = fullText.take(MAX_TEXT_LENGTH)
    if (!SHIPPING_WORD.containsMatchIn(text)) return emptyList()

    return listOf(
        matches(text, UPS_NUMBER, Carrier.UPS, ::isValidUps),
        matches(text, USPS_NUMBER, Carrier.USPS, ::isValidUsps),
        matches(text, USPS_INTERNATIONAL_NUMBER, Carrier.USPS, ::isValidS10),
        matches(text, FEDEX_NUMBER, Carrier.FEDEX, ::isValidFedex, FEDEX_WORD),
        matches(text, DHL_NUMBER, Carrier.DHL, ::isValidDhl, DHL_WORD),
    ).flatten()
        .sortedBy { (_, position) -> position }
        .map { (trackingNumber, _) -> trackingNumber }
        .distinct()
}

private fun matches(
    text: String,
    pattern: Regex,
    carrier: Carrier,
    isValid: (String) -> Boolean,
    carrierWord: Regex? = null,
): List<Pair<TrackingNumber, Int>> {
    val carrierWords = carrierWord?.findAll(text)?.map { it.range }?.toList()
    if (carrierWords != null && carrierWords.isEmpty()) return emptyList()

    return pattern.findAll(text)
        .filter { match ->
            carrierWords == null ||
                carrierWords.any { word -> distanceBetween(word, match.range) <= MAX_CARRIER_DISTANCE }
        }
        .map { match -> match.value.filterNot { it == ' ' } to match.range.first }
        .filter { (number, _) -> isValid(number) }
        .map { (number, position) -> TrackingNumber(carrier, number) to position }
        .toList()
}

private fun distanceBetween(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last - 1
    second.last < first.first -> first.first - second.last - 1
    else -> 0
}

/**
 * The UPS check: letters count as `(code - 63) mod 10`, odd positions once and even positions twice.
 */
private fun isValidUps(number: String): Boolean {
    val values = number.drop(UPS_PREFIX_LENGTH).map { char ->
        if (char.isDigit()) char.digitToInt() else (char.code - UPS_LETTER_OFFSET) % DECIMAL
    }
    val sum = values.dropLast(1).withIndex().sumOf { (index, value) -> if (index % 2 == 0) value else 2 * value }
    return (DECIMAL - sum % DECIMAL) % DECIMAL == values.last()
}

/**
 * The USPS check: from the right, digits weighted three and one in turn.
 */
private fun isValidUsps(number: String): Boolean {
    val digits = number.map { it.digitToInt() }
    val sum = digits.dropLast(1).reversed().withIndex()
        .sumOf { (index, digit) -> if (index % 2 == 0) USPS_ODD_WEIGHT * digit else digit }
    return (DECIMAL - sum % DECIMAL) % DECIMAL == digits.last()
}

/**
 * The UPU S10 check on the eight serial digits.
 */
private fun isValidS10(number: String): Boolean {
    val digits = number.substring(2, 11).map { it.digitToInt() }
    val sum = digits.take(S10_WEIGHTS.size).withIndex().sumOf { (index, digit) -> digit * S10_WEIGHTS[index] }
    val check = when (val remainder = S10_MODULUS - sum % S10_MODULUS) {
        S10_MODULUS - 1 -> 0
        S10_MODULUS -> 5
        else -> remainder
    }
    return check == digits.last()
}

/**
 * The FedEx Express check: from the right, digits weighted one, three and seven in turn, modulo 11. Ground numbers
 * carry no check that is publicly documented, so only the carrier's name next to them counts.
 */
private fun isValidFedex(number: String): Boolean {
    if (number.length != FEDEX_EXPRESS_LENGTH) return true

    val digits = number.map { it.digitToInt() }
    val sum = digits.dropLast(1).reversed().withIndex()
        .sumOf { (index, digit) -> digit * FEDEX_WEIGHTS[index % FEDEX_WEIGHTS.size] }
    return sum % S10_MODULUS % DECIMAL == digits.last()
}

/**
 * The DHL Express check: the first nine digits as a number, modulo 7.
 */
private fun isValidDhl(number: String): Boolean {
    return number.dropLast(1).toLong() % DHL_MODULUS == number.last().digitToInt().toLong()
}
