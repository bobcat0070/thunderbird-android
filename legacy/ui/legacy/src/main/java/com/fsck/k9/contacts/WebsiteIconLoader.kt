package com.fsck.k9.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.preference.GeneralSettingsManager
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The icon service Chrome itself uses.
 *
 * Chosen over the alternatives on two grounds. It always answers with a PNG, where DuckDuckGo's service
 * returns whatever the site publishes - often a Windows ICO, which Android cannot decode at all. And its
 * placeholder is a single fixed image, so a domain it does not know can be told apart from one it does;
 * DuckDuckGo returns its own generic globe for an unknown domain with no way to distinguish it from a real
 * icon, which would have shown a globe for a third of the senders tried by hand.
 *
 * `fallback_opts` is required rather than optional: without it the service returns the placeholder for every
 * domain, known or not.
 */
const val WEBSITE_ICON_BASE_URL = "https://t2.gstatic.com/faviconV2"

/**
 * SHA-256 of the placeholder the service returns for a domain it has no icon for - a grey globe, 726 bytes.
 *
 * Pinned because the response is a normal 200 and carries nothing else to distinguish it: no header, no
 * status, no length that a real icon could not also have. Re-derive it by requesting the URL below for a
 * domain that cannot exist, e.g. `https://invalid.invalid`. If the service ever changes the image this stops
 * matching and unknown senders show a globe instead of their initial, which is wrong but not unsafe.
 */
private const val PLACEHOLDER_DIGEST = "59bfe9bc385ad69f50793ce4a53397316d7a875a7148a63c16df9b674c6cda64"

/**
 * Namespaces this loader's entries in the shared cache.
 */
private const val CACHE_PREFIX = "website-icon:"

private const val TAG = "WebsiteIconLoader"

/**
 * Identifies the app rather than the HTTP library, which is what a well-behaved client does.
 */
private const val USER_AGENT = "Thunderbird-Android"

private const val ACCEPT = "image/*"

/**
 * One of the two ways the service says it has no icon for a domain; the other is its placeholder image.
 */
private const val HTTP_NOT_FOUND = 404

/**
 * How far up the domain to walk before giving up. Covers the shapes bulk senders use - a sending subdomain,
 * its parent, and one above that - without turning one unknown sender into an unbounded run of requests.
 */
private const val MAX_LOOKUPS = 3

/**
 * The size asked of the service.
 *
 * Fixed rather than taken from the caller, because the response is cached by domain and a second caller
 * wanting a different size would otherwise either miss the cache or silently get the first size anyway. Large
 * enough for the biggest avatar drawn, and the bitmap is scaled to fit wherever it is used.
 */
private const val REQUESTED_SIZE = 128

/**
 * Falls back to the icon of the sender domain's website.
 *
 * Most senders publish no brand indicator, and their website has an icon: this is what closes the gap between
 * a mailbox where a few senders are recognisable and one where most are.
 *
 * Asked last, after a mark an authority vouched for and after a picture the sender chose, because it is the
 * weakest thing shown. Nobody attested to it, and it is not published as a mail identity at all - only as
 * what a browser tab shows for that website. The caller badges it accordingly.
 *
 * Off unless the user turns it on, because the lookup tells a third party which domains this device gets mail
 * from.
 */
class WebsiteIconLoader(
    private val generalSettingsManager: GeneralSettingsManager,
    private val httpClient: OkHttpClient,
    private val cache: AvatarCache,
    private val logger: Logger,
    private val baseUrl: String = WEBSITE_ICON_BASE_URL,
) {

    /**
     * @param senderDomain the domain of the sender's address, which must already have been checked to have
     *   passed DMARC - otherwise a message merely claiming to be from a domain would borrow its icon.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun loadIcon(senderDomain: String): Bitmap? {
        if (!isEnabled()) return null

        val domain = senderDomain.trim().lowercase()
        if (domain.isEmpty()) return null

        cache.get(CACHE_PREFIX + domain)?.let { cached ->
            return if (cached.isEmpty()) null else decode(cached)
        }

        return try {
            resolve(domain)
        } catch (e: Exception) {
            // Anything from a DNS failure to a truncated image. Not cached either way: an outage says
            // nothing about whether this domain has an icon.
            logger.debug(TAG, e) { "Could not load website icon" }
            null
        }
    }

    /**
     * Looks for an icon at the sending domain, then at each parent of it.
     *
     * Bulk mail is almost never sent from the domain whose website people know: of the sending domains in one
     * real mailbox, `e.olivegarden.com`, `email.rocketmoney.com`, `mg.homedepot.com` and
     * `notifications.creditkarma.com` all have no icon of their own while their parents all do. Without this
     * walk the feature would find nothing for exactly the senders it exists to help.
     *
     * The exact domain is still tried first, because some sending subdomains do have their own icon -
     * `snacks.robinhood.com` differs from `robinhood.com`.
     *
     * Walking stops two labels from the end rather than at a registrable domain, because knowing where the
     * registrable part starts needs the public suffix list. Overshooting is harmless in practice: the service
     * has no icon for `co.uk`, `com.au` or `org.uk`, so those attempts come back as misses like any other
     * unknown domain, and `tesco.co.uk` is still reached on the way past.
     */
    private fun resolve(senderDomain: String): Bitmap? {
        return when (val found = search(senderDomain.andParents())) {
            is FetchResult.Icon -> {
                // Remembered under the sending domain too, so the next message from this sender is one cache
                // hit rather than another walk.
                cache.put(CACHE_PREFIX + senderDomain, found.bytes)
                decode(found.bytes)
            }

            FetchResult.NoIcon -> {
                cache.putMiss(CACHE_PREFIX + senderDomain)
                null
            }

            // Nothing is remembered, so the next message from this sender asks again.
            FetchResult.Unavailable -> null
        }
    }

    /**
     * @return the first icon found among [candidates], or why there was none.
     *
     * A service that cannot answer ends the search rather than working up a chain that will fail the same
     * way, and is reported separately from a domain that genuinely has no icon.
     */
    private fun search(candidates: List<String>): FetchResult {
        return candidates
            .asSequence()
            .map { candidate -> lookUp(candidate) }
            .firstOrNull { result -> result != FetchResult.NoIcon }
            ?: FetchResult.NoIcon
    }

    /**
     * Asks the cache before the service, so a parent already looked up for a sibling subdomain is free.
     */
    private fun lookUp(candidate: String): FetchResult {
        val cached = cache.get(CACHE_PREFIX + candidate) ?: return fetch(candidate)

        return if (cached.isEmpty()) FetchResult.NoIcon else FetchResult.Icon(cached)
    }

    /**
     * @return whether an icon would be shown for this domain, without drawing one.
     *
     * Answered from the cache alone, so asking never causes a lookup. The message view uses it to say that
     * the picture beside the sender is unverified; a caption that itself went to the network - and could
     * disagree with the picture already on screen - would be worse than none.
     */
    fun hasCachedIconFor(senderDomain: String): Boolean {
        if (!isEnabled()) return false

        val domain = senderDomain.trim().lowercase()

        return cache.get(CACHE_PREFIX + domain)?.isNotEmpty() == true
    }

    private fun isEnabled(): Boolean = generalSettingsManager.getConfig().websiteIcon.isEnabled

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /**
     * What the service said about exactly one domain.
     *
     * [NoIcon] and [Unavailable] are kept apart because they mean opposite things to the walk: the first is an
     * answer worth remembering and worth moving up the domain for, the second is no answer at all.
     */
    private sealed interface FetchResult {
        data class Icon(val bytes: ByteArray) : FetchResult
        data object NoIcon : FetchResult
        data object Unavailable : FetchResult
    }

    @Suppress("ReturnCount")
    private fun fetch(domain: String): FetchResult {
        val url = "$baseUrl?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$domain" +
            "&size=$REQUESTED_SIZE"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            // The service has two ways of saying it has no icon for a domain, and which one it uses is not
            // ours to predict: from a desktop it answers 200 with its placeholder, while the same request
            // from an emulator gets a plain 404. Both mean the same thing - no icon here - so both are
            // remembered and both let the walk move up to the parent domain. Treating the 404 as a failure
            // instead stopped the walk at the sending subdomain, which is where bulk senders never have one.
            if (response.code == HTTP_NOT_FOUND) {
                cache.putMiss(CACHE_PREFIX + domain)
                return FetchResult.NoIcon
            }

            if (!response.isSuccessful) {
                // Not remembered: a rate limit or an outage says nothing about this domain.
                logger.debug(TAG) { "Website icon service responded ${response.code} for $url" }
                return FetchResult.Unavailable
            }

            val bytes = response.body.bytes()
            if (bytes.isPlaceholder()) {
                // Remembered, because "this domain has no icon" is a real answer and the common one.
                cache.putMiss(CACHE_PREFIX + domain)
                return FetchResult.NoIcon
            }

            cache.put(CACHE_PREFIX + domain, bytes)
            FetchResult.Icon(bytes)
        }
    }

    private fun ByteArray.isPlaceholder(): Boolean = sha256() == PLACEHOLDER_DIGEST
}

/**
 * @return this domain, then each parent of it, stopping two labels from the end and after [MAX_LOOKUPS].
 *
 * The cap bounds both the work and the disclosure: every attempt is another request naming another domain,
 * and three covers the shapes bulk senders actually use.
 */
internal fun String.andParents(): List<String> {
    return generateSequence(this) { domain ->
        domain.substringAfter('.').takeIf { it.count { character -> character == '.' } >= 1 }
    }.take(MAX_LOOKUPS).toList()
}

private fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)

    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
