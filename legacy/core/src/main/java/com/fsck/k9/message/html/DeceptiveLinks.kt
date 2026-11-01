package com.fsck.k9.message.html

import java.net.IDN
import org.jsoup.Jsoup

/**
 * A link's text when it is nothing but a web address or a bare domain, e.g. `paypal.com` or
 * `https://www.paypal.com/signin`. Captures the host.
 */
private val ADDRESS_TEXT = Regex(
    """^(?:https?://)?([\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+)\.?(?::\d+)?(?:[/?#]\S*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Finds the links in a message whose text names one website while the link itself goes to another - the shape of
 * nearly every phishing link: "https://www.yourbank.com" written over an address somewhere else entirely.
 *
 * Only a link whose whole text is an address counts. "Shop now" over a tracking address makes no claim about where
 * it goes, and flagging it would put a warning on every newsletter; `yourbank.com` over one elsewhere does.
 *
 * A subdomain of the named site is the same site - `amazon.com` over `click.e.amazon.com` is Amazon's own mail
 * doing what it always does. Both sides are compared in their ASCII form, so a lookalike written in another
 * alphabet does not pass for the real thing.
 *
 * @return for each host a deceptive link goes to, the site its text named instead.
 */
fun findDeceptiveLinks(html: String): Map<String, String> {
    return Jsoup.parse(html).select("a[href]")
        .mapNotNull { anchor ->
            val targetHost = hostOf(anchor.attr("href")) ?: return@mapNotNull null
            val shownHost = ADDRESS_TEXT.matchEntire(anchor.text().trim())?.groupValues?.get(1)?.let(::asciiHost)
                ?: return@mapNotNull null

            if (isSameSite(shownHost, targetHost)) null else targetHost to shownHost
        }
        .toMap()
}

private val WEB_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

/**
 * Finds the links whose text is itself a web address - `costco.com/orders` written over a click-tracking address -
 * so the reader can be offered the address they were shown rather than the one behind it.
 *
 * @return for each link, keyed by its `href` as [canonicalWebLink] writes it, the address its text names, as an
 *   `https` link when the text gave no scheme. Links whose text is anything else are left out.
 */
fun findAddressTexts(html: String): Map<String, String> {
    return Jsoup.parse(html).select("a[href]")
        .mapNotNull { anchor ->
            val href = canonicalWebLink(anchor.attr("href")) ?: return@mapNotNull null
            val text = anchor.text().trim()
            if (hostOf(href) == null || ADDRESS_TEXT.matchEntire(text) == null) return@mapNotNull null

            val address = if (WEB_SCHEME.containsMatchIn(text)) text else "https://$text"
            hostOf(address)?.let { href to address }
        }
        .toMap()
}

private val LINK_SCHEME = Regex("^(https?):", RegexOption.IGNORE_CASE)
private val TAB_OR_NEWLINE = Regex("[\t\n\r]")
private val PERCENT_ENCODED = Regex("(?:%[0-9A-Fa-f]{2})+")

/** Characters that end a link's host: a backslash too, as browsers read web links. */
private const val AUTHORITY_END = "/\\?#"

/**
 * A web link written the way a browser reads it, or `null` for anything that is not a web link.
 *
 * A link in a message is written however its sender likes, and browsers forgive a lot: tabs and line breaks inside
 * it are dropped, any number of slashes or backslashes may follow the scheme (`https:evil.example`,
 * `https:\\evil.example`), and a backslash before the query ends the host just as a slash does. A check that reads
 * the link more literally than the browser that opens it can be shown one site while the reader is sent to another:
 * `https://evil.example\@paypal.com/` opens `evil.example`. Everything that judges a link's site reads it in this
 * form, and a link is opened in it, so what was checked is what is opened.
 */
fun canonicalWebLink(url: String): String? {
    val cleaned = url.trim { it <= ' ' }.replace(TAB_OR_NEWLINE, "")
    val scheme = LINK_SCHEME.find(cleaned) ?: return null

    val afterScheme = cleaned.substring(scheme.range.last + 1).trimStart('/', '\\')
    val authorityEnd = afterScheme.indexOfFirst { it in AUTHORITY_END }.takeIf { it >= 0 } ?: afterScheme.length
    val rest = afterScheme.substring(authorityEnd)
    val pathEnd = rest.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: rest.length
    val path = rest.substring(0, pathEnd).replace('\\', '/')

    return scheme.groupValues[1].lowercase() + "://" + afterScheme.substring(0, authorityEnd) + path +
        rest.substring(pathEnd)
}

/**
 * The host a link opens, in the form [findDeceptiveLinks] keys it by, or `null` for anything that is not a web
 * link. Read as a browser reads it: see [canonicalWebLink].
 */
fun hostOf(url: String): String? {
    val canonical = canonicalWebLink(url) ?: return null
    val authority = canonical.substringAfter("://").takeWhile { it !in AUTHORITY_END }
    // Credentials end at the last "@", not the first: a browser reads `paypal.com@x@evil.example` as evil.example.
    val hostAndPort = authority.substringAfterLast('@')
    val host = if (hostAndPort.startsWith('[')) {
        hostAndPort.substringBefore(']') + "]"
    } else {
        hostAndPort.substringBefore(':')
    }

    return asciiHost(percentDecoded(host))
}

/**
 * Browsers decode a host before looking it up, so `evil%2Eexample` is `evil.example`.
 */
private fun percentDecoded(host: String): String {
    return PERCENT_ENCODED.replace(host) { match ->
        val bytes = match.value.split('%').drop(1).map { it.toInt(radix = 16).toByte() }.toByteArray()
        String(bytes, Charsets.UTF_8)
    }
}

@Suppress("SwallowedException")
private fun asciiHost(host: String): String? {
    return try {
        IDN.toASCII(host.trimEnd('.'), IDN.ALLOW_UNASSIGNED).lowercase().removePrefix("www.").takeIf { it.isNotEmpty() }
    } catch (e: IllegalArgumentException) {
        // Not a name that can be written in ASCII at all, which is not a host anyone can link to.
        null
    }
}

private fun isSameSite(shownHost: String, targetHost: String): Boolean {
    return targetHost == shownHost || targetHost.endsWith(".$shownHost")
}
