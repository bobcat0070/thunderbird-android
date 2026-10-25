package com.fsck.k9.message.html

import java.net.URLDecoder

/**
 * Query parameters that exist only to tell a sender or an ad network who clicked, and that no site needs to show the
 * page. Matched without regard to case. Kept to names that are tracking and nothing else: a parameter some sites
 * also use to work, like `ref` or `source`, is left alone, because stripping it could break the link.
 */
private val TRACKING_PARAMETERS = setOf(
    // Google Ads and Analytics
    "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "_ga", "_gl", "srsltid",
    // Microsoft Advertising, Facebook, Instagram, Twitter, TikTok, LinkedIn, Yandex
    "msclkid", "fbclid", "igshid", "twclid", "ttclid", "li_fat_id", "yclid",
    // Mailchimp, HubSpot, Marketo, Vero, MailerLite, Omeda, Adobe
    "mc_cid", "mc_eid", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsctatracking", "mkt_tok", "vero_id",
    "vero_conv", "ml_subscriber", "ml_subscriber_hash", "oly_anon_id", "oly_enc_id", "s_cid", "rb_clickid",
)

/**
 * Any parameter starting with this is Google's campaign tagging, which every mailing tool writes.
 */
private const val TRACKING_PARAMETER_PREFIX = "utm_"

/**
 * How many redirectors deep a link is unwrapped. Safe Links around a Google redirect is two; more is not a real link.
 */
private const val MAX_UNWRAP_DEPTH = 3

/**
 * A service that sends every click through itself, with the real destination as one of its parameters.
 *
 * @param host the redirector's host in the form [hostOf] gives it, or a suffix of it starting with a dot.
 * @param path the path it answers on, or `null` for any.
 * @param parameter the parameter that holds the destination.
 */
private class Redirector(val host: String, val path: String?, val parameter: String) {
    fun matches(linkHost: String, linkPath: String): Boolean {
        val hostMatches = if (host.startsWith(".")) linkHost.endsWith(host) else linkHost == host
        return hostMatches && (path == null || linkPath == path)
    }
}

/**
 * Microsoft Safe Links is first because Microsoft 365 wraps every link in a message with it. Its `data` parameter
 * carries the recipient's address, which is a reason of its own to unwrap it.
 */
private val REDIRECTORS = listOf(
    Redirector(".safelinks.protection.outlook.com", path = null, parameter = "url"),
    Redirector("google.com", path = "/url", parameter = "q"),
    Redirector("google.com", path = "/url", parameter = "url"),
    Redirector("l.facebook.com", path = "/l.php", parameter = "u"),
    Redirector("lm.facebook.com", path = "/l.php", parameter = "u"),
    Redirector("l.instagram.com", path = null, parameter = "u"),
    Redirector("youtube.com", path = "/redirect", parameter = "q"),
    Redirector("linkedin.com", path = "/redir/redirect", parameter = "url"),
    Redirector("out.reddit.com", path = null, parameter = "url"),
)

/**
 * Domains of mailing services that count clicks by sending each one through an address of their own, with the
 * destination hidden in an opaque token. Matched against the host and each domain it is under.
 */
private val CLICK_TRACKING_DOMAINS = setOf(
    "list-manage.com", "mlsend.com", "sendgrid.net", "klclick.com", "klclick1.com", "klclick2.com", "klclick3.com",
    "hubspotlinks.com", "mandrillapp.com", "sparkpostmail.com", "rs6.net", "createsend.com", "cmail19.com",
    "cmail20.com", "awstrack.me", "exct.net", "customeriomail.com", "t.co",
)

/**
 * The first label of a host a sender's mailing service set up on the sender's own domain to count clicks, as in
 * `click.e.example.com` or `links.example.com`. `link` and `track` are left out: `link.springer.com` is a site, and
 * `track.` is as often a parcel's tracking page.
 */
private val CLICK_TRACKING_LABELS = setOf("click", "clicks", "ctrk", "trk", "links")

/**
 * Paths mailing services count clicks on when they put the tracker on the sender's own domain, as in
 * `ablink.email.example.com/uni/ls/click?upn=...` - SendGrid's `/ls/click`, `/wf/click` and `/ss/c/`, and
 * Mailchimp's `/track/click`.
 */
private val CLICK_TRACKING_PATH = Regex("""(?:/ls/click|/wf/click|/track/click)(?:$|[/?])|^/ss/c/""")

/**
 * How long a link may be before it is shown shortened; about four lines of a dialog on a phone.
 */
private const val MAX_DISPLAYED_LINK_LENGTH = 160

/**
 * How much of the end of a shortened link is kept, so a link that differs only at the end still looks different.
 */
private const val DISPLAYED_LINK_TAIL_LENGTH = 32

private val WEB_LINK = Regex("""^https?://""", RegexOption.IGNORE_CASE)
private val PATH = Regex("""^https?://[^/?#]*(/[^?#]*)?""", RegexOption.IGNORE_CASE)

/**
 * A web link with what tracks the click taken out.
 *
 * @property url the link to open instead.
 * @property removedParameters the names of the tracking parameters taken out, as the link wrote them.
 * @property skippedRedirects the hosts of the redirectors skipped, outermost first.
 */
data class StrippedLink(
    val url: String,
    val removedParameters: List<String>,
    val skippedRedirects: List<String>,
)

/**
 * Takes out of a web link the parts that only track who clicked it: the redirectors that sit in front of the real
 * destination - when the destination is written into the link, so skipping them changes nothing about where it
 * goes - and the tracking parameters on whatever is left.
 *
 * Nothing else is touched. Every other parameter is kept exactly as it was written, encoding included, because a
 * site may need it; and a click-tracking address that hides its destination is left as it is, since nothing can be
 * removed from it without breaking it.
 *
 * @return the stripped link, or `null` when there is nothing to take out or [url] is not a web link.
 */
fun stripTracking(url: String): StrippedLink? {
    val original = url.trim()
    if (!WEB_LINK.containsMatchIn(original)) return null

    var current = original
    val skippedRedirects = mutableListOf<String>()
    while (skippedRedirects.size < MAX_UNWRAP_DEPTH) {
        val destination = redirectDestination(current) ?: break
        skippedRedirects += hostOf(current).orEmpty()
        current = destination
    }

    val (withoutTracking, removed) = removeTrackingParameters(current)

    // Judged by what was taken out, not by whether the text changed: dropping an empty `?` changes the text and
    // removes nothing, and offering that as stripped would show the same link twice.
    return if (removed.isEmpty() && skippedRedirects.isEmpty()) {
        null
    } else {
        StrippedLink(url = withoutTracking, removedParameters = removed, skippedRedirects = skippedRedirects)
    }
}

/**
 * Recognises a link that goes through a click-tracking service which hides its destination - the kind most
 * newsletter links are. Such a link cannot be stripped, since the token that tracks the click is also the only
 * record of where it goes; this is for saying so rather than calling the link clean.
 *
 * @return the tracking service's host, or `null` when [url] does not look like one.
 */
fun clickTrackerHost(url: String): String? {
    val host = hostOf(url) ?: return null
    val labels = host.split('.')
    val isKnownService = labels.indices.any { start -> labels.drop(start).joinToString(".") in CLICK_TRACKING_DOMAINS }
    val isTrackingSubdomain = labels.size > 2 && labels.first() in CLICK_TRACKING_LABELS
    val path = PATH.find(url)?.groupValues?.get(1).orEmpty()
    val isSubstackRedirect = host.endsWith("substack.com") && path.startsWith("/redirect/")
    val isTrackingPath = CLICK_TRACKING_PATH.containsMatchIn(path)

    return host.takeIf { isKnownService || isTrackingSubdomain || isSubstackRedirect || isTrackingPath }
}

/**
 * A link as it can be shown in a dialog: as it is when it is short, otherwise its start and end with the middle
 * left out. Tracking links run to a thousand characters of token, and shown whole they push everything else off the
 * screen; the start says where the link goes, and the end tells two otherwise similar links apart.
 */
fun shortenForDisplay(url: String): String {
    if (url.length <= MAX_DISPLAYED_LINK_LENGTH) return url

    val head = url.take(MAX_DISPLAYED_LINK_LENGTH - DISPLAYED_LINK_TAIL_LENGTH - 1)
    return head + "…" + url.takeLast(DISPLAYED_LINK_TAIL_LENGTH)
}

/**
 * @return where a redirector link sends its click, or `null` when [url] is not one or names no web destination.
 */
private fun redirectDestination(url: String): String? {
    val host = hostOf(url).orEmpty()
    val path = PATH.find(url)?.groupValues?.get(1)?.ifEmpty { "/" } ?: "/"
    val redirector = REDIRECTORS.firstOrNull { it.matches(host, path) } ?: return null

    return queryParameters(url)
        .firstOrNull { (name, _) -> decode(name) == redirector.parameter }
        ?.let { (_, value) -> decode(value).trim() }
        ?.takeIf { WEB_LINK.containsMatchIn(it) }
}

private fun removeTrackingParameters(url: String): Pair<String, List<String>> {
    val queryStart = url.indexOf('?')
    val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
    if (queryStart < 0 || queryStart > fragmentStart) return url to emptyList()

    val base = url.substring(0, queryStart)
    val fragment = url.substring(fragmentStart)
    val removed = mutableListOf<String>()
    val kept = url.substring(queryStart + 1, fragmentStart)
        .split('&')
        .filter { parameter ->
            val name = decode(parameter.substringBefore('='))
            val isTracking = name.lowercase().isTrackingParameter()
            if (isTracking) removed += name
            !isTracking
        }

    val query = kept.joinToString("&")
    return (if (query.isEmpty()) base + fragment else "$base?$query$fragment") to removed
}

private fun String.isTrackingParameter(): Boolean =
    this in TRACKING_PARAMETERS || startsWith(TRACKING_PARAMETER_PREFIX)

/**
 * @return the link's query parameters as written, names and values still encoded.
 */
private fun queryParameters(url: String): List<Pair<String, String>> {
    val queryStart = url.indexOf('?').takeIf { it >= 0 } ?: return emptyList()
    val fragmentStart = url.indexOf('#').takeIf { it > queryStart } ?: url.length

    return url.substring(queryStart + 1, fragmentStart)
        .split('&')
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
}

@Suppress("SwallowedException")
private fun decode(value: String): String {
    return try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        // A stray '%' that starts no escape: the text as written is the best reading of it.
        value
    }
}
