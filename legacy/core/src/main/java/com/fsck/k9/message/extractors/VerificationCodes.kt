package com.fsck.k9.message.extractors

/**
 * Words that say a number next to them is a one-time code, in the languages most such mail comes in. A word that
 * ends in one of the `code` forms counts too, for compounds like `Bestätigungscode` and `Sicherheitscode`.
 */
private val CODE_WORD = Regex(
    "(?iu)(?<!\\p{L})(?:\\p{L}*(?:codes?|c[oó]digos?|codice|kod|kode|koodi|код)|passcodes?|otp|pin|2fa|mfa|" +
        "one-time|v[eé]rif\\p{L}*|authenticat\\p{L}*)(?!\\p{L})",
)

/**
 * Words ending in `code` that are not about signing in.
 */
private val NON_CODE_COMPOUNDS = setOf(
    "barcode", "barcodes", "postcode", "postcodes", "zipcode", "zipcodes", "promocode", "promocodes", "qrcode",
    "unicode", "encode", "decode", "opcode", "sourcecode", "dresscode", "colorcode", "colourcode", "gutscheincode",
    "rabattcode", "aktionscode",
)

/**
 * Words before `code` that make it a shop's, a parcel's or a place's code rather than a sign-in code, as in
 * `promo code 4521` or `postal code 10115`.
 */
private val NON_CODE_QUALIFIERS = setOf(
    "promo", "promotional", "coupon", "discount", "voucher", "gift", "referral", "offer", "bonus", "sale", "zip",
    "postal", "post", "area", "country", "tracking", "order", "reference", "source", "qr", "dress", "color",
    "colour", "product", "item", "style",
)

/**
 * A code of four to eight digits, or two groups of three or four split by a space or a hyphen as in `123 456`. Not
 * part of a longer number, a phone number, a price, a decimal, a time, a percentage, a word or a link.
 */
private val CODE_CANDIDATE = Regex(
    "(?<![\\p{L}\\d#\$€£¥+/=@])(?<!\\d[.,:])(?<!\\d[ -])(\\d{3,4}[ -]\\d{3,4}|\\d{4,8})" +
        "(?!\\d|[.,:/]\\d|[ -]\\d|%|\\p{L})",
)

/**
 * How far a code may be from the word that names it, in characters between them. Enough for
 * `verification code for your account is: 123456`, too little to reach a number in another sentence.
 */
private const val MAX_DISTANCE = 40

/**
 * How much of each text is read; codes come early, and this keeps a long subject cheap.
 */
private const val MAX_TEXT_LENGTH = 1024

/**
 * Four-digit numbers in this range read as years, as in `Verify your account by March 2027`, and are not taken.
 */
private const val FIRST_YEAR = 1900
private const val LAST_YEAR = 2099
private const val YEAR_LENGTH = 4

/**
 * Finds the one-time code in a sign-in or verification mail, so it can be copied without opening the message.
 *
 * Looks in [subject] first, where most services put the code, then in [text], the message's preview. A number
 * counts only when a word like `code`, `PIN` or `verification` is next to it, and only digits are taken - codes
 * with letters in them are rare and look too much like product and booking numbers. A four-digit number that reads
 * as a year is left alone.
 *
 * Says nothing about whether the sender is who they claim; callers must not offer the code for a message flagged
 * as an impersonation or from a threat-listed sender.
 *
 * @return the code's digits without separators, or `null` when no code is found.
 */
fun findVerificationCode(subject: String?, text: String?): String? {
    return subject?.let(::findCodeIn) ?: text?.let(::findCodeIn)
}

private fun findCodeIn(fullText: String): String? {
    val text = fullText.take(MAX_TEXT_LENGTH)
    val codeWords = CODE_WORD.findAll(text).filter { isCodeWord(text, it) }.toList()
    if (codeWords.isEmpty()) return null

    return CODE_CANDIDATE.findAll(text)
        .filterNot { it.value.length == YEAR_LENGTH && it.value.toInt() in FIRST_YEAR..LAST_YEAR }
        .mapNotNull { candidate ->
            val distance = codeWords.minOf { word -> distanceBetween(word.range, candidate.range) }
            if (distance <= MAX_DISTANCE) candidate to distance else null
        }
        .minByOrNull { (_, distance) -> distance }
        ?.first?.value
        ?.filter(Char::isDigit)
}

private fun isCodeWord(text: String, word: MatchResult): Boolean {
    val lowercaseWord = word.value.lowercase()
    if (lowercaseWord in NON_CODE_COMPOUNDS) return false

    val previousWord = text.substring(0, word.range.first).trimEnd().takeLastWhile(Char::isLetter).lowercase()
    return previousWord !in NON_CODE_QUALIFIERS
}

private fun distanceBetween(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last - 1
    second.last < first.first -> first.first - second.last - 1
    else -> 0
}
