package com.fsck.k9.message.html

import net.thunderbird.feature.blocklist.DomainBlocklist
import org.jsoup.parser.Parser

/**
 * Services that put an open-tracking pixel in mail, by the domain their pixels are served from, with the name to
 * show for them. Matched against the image's host and each domain it is under.
 */
private val TRACKING_SERVICES = mapOf(
    "list-manage.com" to "Mailchimp",
    "mandrillapp.com" to "Mailchimp",
    "sendgrid.net" to "SendGrid",
    "hubspotemail.net" to "HubSpot",
    "hubspotlinks.com" to "HubSpot",
    "hs-analytics.net" to "HubSpot",
    "exct.net" to "Salesforce",
    "awstrack.me" to "Amazon SES",
    "customeriomail.com" to "Customer.io",
    "klaviyomail.com" to "Klaviyo",
    "klclick.com" to "Klaviyo",
    "sparkpostmail.com" to "SparkPost",
    "createsend.com" to "Campaign Monitor",
    "cmail19.com" to "Campaign Monitor",
    "cmail20.com" to "Campaign Monitor",
    "mlsend.com" to "MailerLite",
    "rs6.net" to "Constant Contact",
    "mailtrack.io" to "Mailtrack",
    "mailsuite.com" to "Mailsuite",
    "yesware.com" to "Yesware",
    "mixmax.com" to "Mixmax",
    "superhuman.com" to "Superhuman",
    "mailfoogae.appspot.com" to "Streak",
    "bananatag.com" to "Bananatag",
    "getnotify.com" to "Notify",
    "emltrk.com" to "Litmus",
)

/**
 * Paths that mailing services count opens on, wherever they are hosted - often on the sender's own domain, as in
 * `email.example.com/wf/open?upn=...`: SendGrid's `/wf/open`, Mailchimp's and Mandrill's `/track/open.php`,
 * Salesforce's `/open.aspx` and Customer.io's `/e/o/`.
 */
private val OPEN_TRACKING_PATH = Regex("""(?:/wf/open|/track/open(?:\.php)?|/open\.aspx)(?:$|[/?])|^/e/o/""")

private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ATTRIBUTE = Regex("""\s([a-zA-Z][\w:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val PATH = Regex("""^https?://[^/?#]*(/[^?#]*)?""", RegexOption.IGNORE_CASE)
private val WEB_LINK = Regex("""^https?://""", RegexOption.IGNORE_CASE)
private val CSS_DIMENSION = Regex(
    """(?:^|;)\s*(width|height|max-width|max-height)\s*:\s*(\d+(?:\.\d+)?)(px)?\s*(?:!important)?\s*(?=;|$)""",
)
private val CSS_HIDDEN = Regex(
    """(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(?:\.0+)?)\s*(?:!important)?\s*""" +
        """(?=;|$)""",
)

/**
 * Images no bigger than this, in pixels each way, show nothing a reader could see; a sender only puts one in to
 * learn when the message is opened.
 */
private const val MAX_PIXEL_SIZE = 2

/**
 * What a blocked image's `src` and `srcset` are renamed to, so the web view never asks for them and the message
 * can still be shown as sent when the reader asks for its trackers.
 */
private const val BLOCKED_ATTRIBUTE_PREFIX = "data-blocked-"

/**
 * An image a sender put in a message to learn when, where and on what it is opened.
 *
 * @property url the image's address.
 * @property service who runs it: the tracking service's name when it is a known one, or else the image's host.
 */
data class TrackingImage(
    val url: String,
    val service: String,
)

/**
 * @property html the message with its tracking images left unable to load, or as it was when it has none.
 * @property trackers the tracking images found, in the order they appear.
 */
data class BlockedTrackingImages(
    val html: String,
    val trackers: List<TrackingImage>,
) {
    /**
     * Who runs the trackers, each named once, most often found first.
     */
    val services: List<String>
        get() = trackers.groupingBy { it.service }.eachCount().entries
            .sortedByDescending { it.value }
            .map { it.key }
}

/**
 * Finds the images in a message that are there to track the reader rather than to be seen - an image of a pixel or
 * none, one that is hidden, one served by a tracking service or on a tracking path, or one from a domain on the
 * reader's blocklist - and takes away their address, so they cannot load even when the message's images are shown.
 *
 * Reads the HTML as the sanitizer writes it, with each tag well formed. Only `<img>` tags are looked at; anything
 * else in the message is left exactly as it was.
 *
 * @param blocklist the domain blocklist the reader chose, or `null` to go by the other signs only.
 */
fun blockTrackingImages(html: String, blocklist: DomainBlocklist? = null): BlockedTrackingImages {
    val trackers = mutableListOf<TrackingImage>()
    val blockedHtml = IMG_TAG.replace(html) { tag ->
        val attributes = ATTRIBUTE.findAll(tag.value).associate { attribute ->
            val value = attribute.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            attribute.groupValues[1].lowercase() to Parser.unescapeEntities(value, true)
        }
        val url = attributes["src"]?.trim()?.takeIf { WEB_LINK.containsMatchIn(it) }
        val tracker = url?.let { trackingImage(it, attributes, blocklist) }

        if (tracker == null) {
            tag.value
        } else {
            trackers += tracker
            blockSources(tag.value)
        }
    }

    return BlockedTrackingImages(html = if (trackers.isEmpty()) html else blockedHtml, trackers = trackers)
}

private fun trackingImage(url: String, attributes: Map<String, String>, blocklist: DomainBlocklist?): TrackingImage? {
    val host = hostOf(url) ?: return null
    val service = knownService(host)
    val path = PATH.find(url)?.groupValues?.get(1).orEmpty()

    val isTracker = service != null ||
        OPEN_TRACKING_PATH.containsMatchIn(path) ||
        isInvisible(attributes) ||
        blocklist?.match(host) != null

    return if (isTracker) TrackingImage(url = url, service = service ?: host) else null
}

private fun knownService(host: String): String? {
    val labels = host.split('.')
    return labels.indices.firstNotNullOfOrNull { start -> TRACKING_SERVICES[labels.drop(start).joinToString(".")] }
}

private fun isInvisible(attributes: Map<String, String>): Boolean {
    val style = attributes["style"].orEmpty().lowercase()
    if (CSS_HIDDEN.containsMatchIn(style)) return true

    val cssSizes = CSS_DIMENSION.findAll(style)
        .filter { it.groupValues[3].isNotEmpty() || it.groupValues[2].toDouble() == 0.0 }
        .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
    val width = cssSizes["width"] ?: cssSizes["max-width"] ?: attributes["width"]?.let(::pixels)
    val height = cssSizes["height"] ?: cssSizes["max-height"] ?: attributes["height"]?.let(::pixels)

    return width == 0.0 ||
        height == 0.0 ||
        (width != null && height != null && width <= MAX_PIXEL_SIZE && height <= MAX_PIXEL_SIZE)
}

/**
 * A `width` or `height` attribute in pixels, which is what a bare number or one ending in `px` means.
 */
private fun pixels(value: String): Double? = value.trim().removeSuffix("px").trim().toDoubleOrNull()

private fun blockSources(tag: String): String {
    return ATTRIBUTE.replace(tag) { attribute ->
        val name = attribute.groupValues[1]
        if (name.equals("src", ignoreCase = true) || name.equals("srcset", ignoreCase = true)) {
            attribute.value.replaceFirst(name, BLOCKED_ATTRIBUTE_PREFIX + name.lowercase())
        } else {
            attribute.value
        }
    }
}
