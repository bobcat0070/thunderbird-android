package net.thunderbird.feature.packages.internal

import net.thunderbird.feature.packages.ShippingMail

/**
 * The domain USPS sends its Informed Delivery daily digest from.
 */
private const val DIGEST_DOMAIN = "informeddelivery.usps.com"

/**
 * The headings the digest lists packages under, each with the status a package under it has. "Expected Today" is the
 * post office's word that the package arrives today, which is what out for delivery tells the reader.
 */
private val DIGEST_SECTIONS = listOf(
    Regex("""(?im)^[ \t]*Expected Today\b""") to ParcelStatus.OUT_FOR_DELIVERY,
    Regex("""(?im)^[ \t]*Expected \d+(?:-\d+)? Days?\b""") to ParcelStatus.ON_ITS_WAY,
    Regex("""(?im)^[ \t]*Awaiting From Sender\b""") to ParcelStatus.ON_ITS_WAY,
    Regex("""(?im)^[ \t]*Outbound\b""") to ParcelStatus.ON_ITS_WAY,
)

/**
 * The digest names the shipper of each package on a line of its own above its tracking number: "FROM: EXAMPLE SHOP".
 */
private val DIGEST_SHIPPER = Regex("""(?im)^[ \t]*FROM:[ \t]*([^\n]+?)[ \t]*$""")

/**
 * A tracking number in the digest: the one above a package's own, whose "FROM:" line is not this package's.
 */
private val DIGEST_NUMBER = Regex("""\d{20,}""")

/**
 * Whether [mail] is the Informed Delivery daily digest, which lists several packages, each under a heading that says
 * when it is expected: what one says about a package is not what it says about the others.
 */
internal fun isInformedDeliveryDigest(mail: ShippingMail): Boolean {
    val domain = mail.senderAddress?.substringAfterLast('@', missingDelimiterValue = "")?.lowercase() ?: return false
    return domain == DIGEST_DOMAIN || domain.endsWith(".$DIGEST_DOMAIN")
}

/**
 * What the digest's [text] says about the package [number]: the status of the heading it is listed under, or `null`
 * when it is not listed in the text, or under no heading.
 */
internal fun digestStatus(text: String, number: String): ParcelStatus? {
    val position = text.indexOf(number).takeIf { it >= 0 } ?: return null
    val before = text.substring(0, position)
    return DIGEST_SECTIONS
        .mapNotNull { (heading, status) -> heading.findAll(before).lastOrNull()?.let { it.range.first to status } }
        .maxByOrNull { (start, _) -> start }
        ?.second
}

/**
 * The shipper the digest's [text] names for the package [number]: the "FROM:" line above it and below the package
 * before it, or `null`.
 */
internal fun digestShipper(text: String, number: String): String? {
    val position = text.indexOf(number).takeIf { it >= 0 } ?: return null
    val before = text.substring(0, position)
    val packageStart = DIGEST_NUMBER.findAll(before).lastOrNull()?.range?.last?.plus(1) ?: 0
    return DIGEST_SHIPPER.findAll(before.substring(packageStart)).lastOrNull()
        ?.groupValues?.get(1)
        ?.let(::plainShipperName)
}
