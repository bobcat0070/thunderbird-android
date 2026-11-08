package net.thunderbird.feature.packages.internal

import net.thunderbird.feature.packages.ShippingMail

/**
 * The domains the carriers send their own tracking mail from.
 */
private val CARRIER_DOMAINS = listOf("ups.com", "usps.com", "fedex.com", "dhl.com", "dhl.de")

private const val MAX_SHIPPER_LENGTH = 60

/**
 * How carriers name the shipper in their mail: UPS on a line of its own, "From ACME GADGETS LIMITED"; USPS the
 * same way, "Package Shipped from: EXAMPLE PHARMACY"; FedEx in a sentence, "Your shipment from EXAMPLE AUTO PARTS
 * LLC is out for delivery today".
 */
private val SHIPPER_PATTERNS = listOf(
    Regex(
        """(?im)^[ \t]*(?:package[ \t]+)?(?:from|shipper|shipped[ \t]+by|shipped[ \t]+from)""" +
            """[ \t]*:?[ \t]+([^\n]+?)[ \t]*$""",
    ),
    Regex(
        """(?i)\b(?:shipment|package|parcel|delivery|item|order)[ \t]+from[ \t]+([^\n]+?)""" +
            """[ \t]+(?:is|was|has|will|are)\b""",
    ),
)

/**
 * Who a parcel is from, as the mail about it says, oldest mail first: the shop that sent a mail about it, or when only
 * the carrier wrote, the shipper the carrier's mail names, or else whoever sent the first mail.
 */
internal fun parcelSender(mailsOldestFirst: List<ShippingMail>): String? {
    return mailsOldestFirst.firstOrNull { !isFromCarrier(it.senderAddress) }?.senderName
        ?: mailsOldestFirst.firstNotNullOfOrNull { shipperNamedIn(it.text) }
        ?: mailsOldestFirst.firstOrNull()?.senderName
}

private fun isFromCarrier(senderAddress: String?): Boolean {
    val domain = senderAddress?.substringAfterLast('@', missingDelimiterValue = "")?.lowercase() ?: return false
    return CARRIER_DOMAINS.any { carrierDomain -> domain == carrierDomain || domain.endsWith(".$carrierDomain") }
}

/**
 * The shipper a carrier's mail names, or `null`. The name is the sender's text, so it is shown as one plain line.
 */
internal fun shipperNamedIn(text: String): String? {
    return SHIPPER_PATTERNS.asSequence()
        .mapNotNull { pattern -> pattern.find(text)?.groupValues?.get(1) }
        .map { name -> name.replace(Regex("""\p{Cf}"""), "").replace(Regex("""\s+"""), " ").trim(' ', '.', ',', ':') }
        .firstOrNull { name -> name.isNotEmpty() && name.length <= MAX_SHIPPER_LENGTH && '@' !in name }
}
