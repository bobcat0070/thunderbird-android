package com.fsck.k9.contacts.bimi

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.fsck.k9.contacts.AvatarCache
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.preference.GeneralSettingsManager
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "BimiLogoLoader"

/**
 * Namespaces this loader entries in the shared cache.
 */
private const val CACHE_PREFIX = "bimi:"

/**
 * The trust tier is stored as a single leading byte in front of the mark, so one cache entry answers both
 * what the logo is and what it was worth.
 */
private const val TIER_BYTE_LENGTH = 1

/**
 * Certificates are small. The cap is what stops a hostile or broken host from streaming an unbounded body
 * into a list row's decode.
 */
private const val MAX_CERTIFICATE_BYTES = 256L * 1024L

private const val USER_AGENT = "Thunderbird-Android"

/**
 * Fetches a sender domain's brand indicator.
 *
 * Only ever called for a message the receiving server reported as passing DMARC. That gate is the whole basis
 * for showing the logo: DMARC is what ties the From domain to a sender the domain authorised, and without it
 * a brand indicator is an assurance about nothing — worse than no logo, because it looks like verification.
 *
 * What a logo is worth depends entirely on who vouched for it, so the three cases are kept apart rather than
 * flattened into one picture:
 *
 * A Verified Mark Certificate means an authority checked that this organisation owns this registered
 * trademark, and the mark drawn is the one inside the certificate, so what appears is exactly what was
 * attested. A Common Mark Certificate means an authority checked something weaker. A domain that publishes a
 * logo with no certificate has only its own say-so, which passing DMARC does not strengthen: DMARC shows a
 * domain authorised its own mail and says nothing about whether it may use the mark. That last case is shown
 * too, but badged, because a lookalike domain passes its own DMARC just as easily as a bank does.
 */
class BimiLogoLoader(
    private val generalSettingsManager: GeneralSettingsManager,
    private val dnsTxtLookup: DnsTxtLookup,
    private val httpClient: OkHttpClient,
    private val cache: AvatarCache,
    private val vmcValidator: VmcValidator,
    private val logger: Logger,
) {

    fun loadLogo(senderDomain: String, size: Int, selector: String = BIMI_DEFAULT_SELECTOR): Bitmap? {
        val mark = markFor(senderDomain, selector) ?: return null

        return renderSvg(mark.svg, size).withMarkBadge(mark.trust)
    }

    /**
     * The mark a domain publishes and how much it is worth, without drawing anything.
     *
     * Separate from [loadLogo] so the message view can say which verification a logo carries without
     * rendering a second copy of it. Both go through the same cache, so asking twice costs one lookup.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun markFor(senderDomain: String, selector: String = BIMI_DEFAULT_SELECTOR): CachedMark? {
        val domain = senderDomain.trim().lowercase()
        if (!generalSettingsManager.getConfig().bimi.isEnabled || domain.isEmpty()) return null

        cache.get(CACHE_PREFIX + domain)?.let { cached -> return cached.toCachedMark() }

        return try {
            fetchMark(domain, selector)
        } catch (e: Exception) {
            // A DNS failure, an unreachable host, or an SVG this renderer cannot read. Not cached: an outage
            // says nothing about what this domain publishes. The caller falls back to the next source.
            logger.debug(TAG, e) { "Could not load BIMI logo" }
            null
        }
    }

    @Suppress("ReturnCount")
    private fun fetchMark(domain: String, selector: String): CachedMark? {
        val record = findRecord(domain, selector)
        if (record == null) {
            cache.putMiss(CACHE_PREFIX + domain)
            return null
        }

        // A certificate that fails to validate leaves exactly what a missing one leaves: the domain's own
        // claim. It is badged the same way rather than being trusted or discarded.
        val mark = record.authorityUrl?.let { downloadVerifiedMark(it, domain) }
        val svg = mark?.svg ?: downloadLogo(record.logoUrl)
        if (svg == null) {
            cache.putMiss(CACHE_PREFIX + domain)
            return null
        }

        val cachedMark = CachedMark(mark.trust(), svg)
        cache.put(CACHE_PREFIX + domain, cachedMark.toBytes())

        return cachedMark
    }

    private fun VerifiedMark?.trust(): MarkTrust = when (this?.assurance) {
        MarkAssurance.VERIFIED -> MarkTrust.VERIFIED
        MarkAssurance.COMMON -> MarkTrust.COMMON
        null -> MarkTrust.SELF_ASSERTED
    }

    /**
     * Fetches the logo a domain publishes directly, for when no certificate vouches for it.
     */
    @Suppress("ReturnCount")
    private fun downloadLogo(logoUrl: String): ByteArray? {
        val request = Request.Builder()
            .url(logoUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/svg+xml")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.debug(TAG) { "BIMI logo host responded ${response.code}" }
                return null
            }

            if (response.body.contentLength() > MAX_CERTIFICATE_BYTES) return null

            response.body.byteStream().readAtMost(MAX_CERTIFICATE_BYTES.toInt())
        }
    }

    /**
     * Fetches the certificate chain and checks it vouches for this domain.
     */
    @Suppress("ReturnCount")
    private fun downloadVerifiedMark(certificateUrl: String, domain: String): VerifiedMark? {
        val request = Request.Builder()
            .url(certificateUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.debug(TAG) { "Mark certificate host responded ${response.code}" }
                return null
            }

            if (response.body.contentLength() > MAX_CERTIFICATE_BYTES) return null

            val bytes = response.body.byteStream().readAtMost(MAX_CERTIFICATE_BYTES.toInt()) ?: return null

            vmcValidator.validate(bytes.inputStream(), domain)
        }
    }

    /**
     * A domain may publish several TXT records at the name; only one is a BIMI record.
     */
    private fun findRecord(domain: String, selector: String): BimiRecord? {
        return dnsTxtLookup.txtRecords(bimiRecordName(domain, selector))
            .firstNotNullOfOrNull { text -> parseBimiRecord(text) }
    }

    /**
     * BIMI mandates the SVG Tiny Portable/Secure profile, which has no scripting and no external references.
     * No external file resolver is installed here, so a logo that asks for one gets nothing rather than
     * causing a fetch this code never intended to make.
     */
    private fun renderSvg(bytes: ByteArray, size: Int): Bitmap {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        svg.setDocumentWidth(size.toFloat())
        svg.setDocumentHeight(size.toFloat())

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))

        return bitmap
    }
}

/**
 * A mark and what it was worth, as held in the cache.
 */
data class CachedMark(val trust: MarkTrust, val svg: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is CachedMark && trust == other.trust && svg.contentEquals(other.svg))

    override fun hashCode(): Int = 31 * trust.hashCode() + svg.contentHashCode()
}

private fun CachedMark.toBytes(): ByteArray = byteArrayOf(trust.ordinal.toByte()) + svg

@Suppress("ReturnCount")
private fun ByteArray.toCachedMark(): CachedMark? {
    // An empty entry is a remembered miss, and anything without a body after the tier byte is unusable.
    if (size <= TIER_BYTE_LENGTH) return null

    val trust = MarkTrust.entries.getOrNull(this[0].toInt()) ?: return null

    return CachedMark(trust, copyOfRange(TIER_BYTE_LENGTH, size))
}

/**
 * @return the stream's bytes, or `null` when it holds more than [limit], so a host that ignores its own
 *   declared content length still cannot stream without end.
 */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
    val buffer = ByteArray(limit + 1)
    var read = 0

    while (read < buffer.size) {
        val count = read(buffer, read, buffer.size - read)
        if (count == -1) break
        read += count
    }

    return if (read > limit) null else buffer.copyOf(read)
}
