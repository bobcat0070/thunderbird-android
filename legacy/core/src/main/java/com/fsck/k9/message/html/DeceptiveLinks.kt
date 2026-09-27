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

/**
 * The host a link opens, in the form [findDeceptiveLinks] keys it by, or `null` for anything that is not a web
 * link.
 */
fun hostOf(url: String): String? {
    val match = Regex("""^https?://(?:[^@/?#]*@)?([^/:?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())

    return match?.groupValues?.get(1)?.let(::asciiHost)
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
