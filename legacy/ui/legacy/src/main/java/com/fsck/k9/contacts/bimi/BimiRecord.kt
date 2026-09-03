package com.fsck.k9.contacts.bimi

/**
 * The subdomain a BIMI record lives under, between the selector and the sender's domain.
 */
private const val BIMI_SUBDOMAIN = "_bimi"

/**
 * The selector used when the message does not name one.
 */
const val BIMI_DEFAULT_SELECTOR = "default"

private const val VERSION_TAG = "v"
private const val LOCATION_TAG = "l"
private const val AUTHORITY_TAG = "a"
private const val SELECTOR_TAG = "s"

private const val BIMI_VERSION = "bimi1"

/**
 * The maximum length of a DNS label, which is what a selector becomes.
 */
private const val MAX_LABEL_LENGTH = 63

/**
 * A brand indicator published by a domain.
 *
 * @param logoUrl where the SVG lives. Always https: a logo fetched over plain http could be swapped in
 *   transit, which would turn the indicator into the opposite of an assurance.
 * @param authorityUrl the Verified Mark Certificate, when the domain published one. Recorded but not yet
 *   checked, so it must not be treated as adding any assurance.
 */
data class BimiRecord(
    val logoUrl: String,
    val authorityUrl: String?,
)

/**
 * @return the DNS name a BIMI record for [domain] is published at.
 */
fun bimiRecordName(domain: String, selector: String = BIMI_DEFAULT_SELECTOR): String =
    "$selector.$BIMI_SUBDOMAIN.$domain"

/**
 * Parses a BIMI DNS record.
 *
 * @return the record, or `null` when the text is not a usable BIMI record. A domain may publish a record with
 *   an empty location to declare that it deliberately has no indicator, which is a valid record and still
 *   nothing to show.
 */
@Suppress("ReturnCount")
fun parseBimiRecord(text: String): BimiRecord? {
    val tags = text.split(';')
        .mapNotNull { part ->
            val name = part.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
            val value = part.substringAfter('=', missingDelimiterValue = "").trim()

            if (name.isEmpty()) null else name to value
        }
        .toMap()

    if (tags[VERSION_TAG]?.lowercase() != BIMI_VERSION) return null

    val logoUrl = tags[LOCATION_TAG]?.takeIf { it.isHttpsUrl() } ?: return null

    return BimiRecord(
        logoUrl = logoUrl,
        authorityUrl = tags[AUTHORITY_TAG]?.takeIf { it.isHttpsUrl() },
    )
}

/**
 * Reads the selector a message asks for.
 *
 * Senders use this to publish different indicators for different mail streams. An unusable header falls back
 * to the default selector rather than suppressing the lookup, matching what the header is for.
 */
fun parseBimiSelector(headerValue: String?): String {
    if (headerValue == null) return BIMI_DEFAULT_SELECTOR

    val selector = headerValue.split(';')
        .firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
            if (name == SELECTOR_TAG) part.substringAfter('=').trim() else null
        }

    return selector?.takeIf { it.isValidSelector() } ?: BIMI_DEFAULT_SELECTOR
}

/**
 * The selector becomes part of a DNS name, so it is restricted to what a label may contain rather than
 * trusted to be well formed.
 */
private fun String.isValidSelector(): Boolean =
    isNotEmpty() && length <= MAX_LABEL_LENGTH && all { it.isLetterOrDigit() || it == '-' || it == '_' }

private fun String.isHttpsUrl(): Boolean = startsWith("https://", ignoreCase = true) && length > "https://".length
