package com.fsck.k9.contacts.bimi

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import java.util.Collections
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.preference.GeneralSettingsManager
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "BimiLogoLoader"

/**
 * How many domains to remember as having no usable indicator.
 *
 * Most domains publish nothing, so without this every scroll past the same sender would repeat a DNS lookup.
 */
private const val MISS_CACHE_SIZE = 512

/**
 * BIMI logos are small by specification. The cap is what stops a hostile or broken host from streaming an
 * unbounded body into a list row's decode.
 */
private const val MAX_LOGO_BYTES = 256L * 1024L

private const val USER_AGENT = "Thunderbird-Android"

/**
 * Fetches a sender domain's brand indicator.
 *
 * Only ever called for a message the receiving server reported as passing DMARC. That gate is the whole basis
 * for showing the logo: DMARC is what ties the From domain to a sender the domain authorised, and without it
 * a brand indicator is an assurance about nothing — worse than no logo, because it looks like verification.
 *
 * The Verified Mark Certificate a domain may publish is deliberately not treated as adding anything here: it
 * is recorded but not validated, so this shows "the domain that passed DMARC publishes this logo" and not
 * "a certificate authority vouched for this mark".
 */
class BimiLogoLoader(
    private val generalSettingsManager: GeneralSettingsManager,
    private val dnsTxtLookup: DnsTxtLookup,
    private val httpClient: OkHttpClient,
    private val logger: Logger,
) {

    private val domainsWithoutLogo: MutableMap<String, Unit> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Unit>?): Boolean = size > MISS_CACHE_SIZE
        },
    )

    @Suppress("TooGenericExceptionCaught")
    fun loadLogo(senderDomain: String, size: Int, selector: String = BIMI_DEFAULT_SELECTOR): Bitmap? {
        val domain = senderDomain.trim().lowercase()
        val shouldLookUp = generalSettingsManager.getConfig().bimi.isEnabled &&
            domain.isNotEmpty() &&
            !domainsWithoutLogo.containsKey(domain)

        if (!shouldLookUp) return null

        return try {
            fetchLogo(domain, size, selector)
        } catch (e: Exception) {
            // A DNS failure, an unreachable host, or an SVG this renderer cannot read. None of them are worth
            // failing the row over: the caller falls back to the next avatar source.
            logger.debug(TAG, e) { "Could not load BIMI logo" }
            null
        }
    }

    private fun fetchLogo(domain: String, size: Int, selector: String): Bitmap? {
        val record = findRecord(domain, selector)
        if (record == null) {
            domainsWithoutLogo[domain] = Unit
            return null
        }

        return downloadLogo(record.logoUrl, size)
    }

    /**
     * A domain may publish several TXT records at the name; only one is a BIMI record.
     */
    private fun findRecord(domain: String, selector: String): BimiRecord? {
        return dnsTxtLookup.txtRecords(bimiRecordName(domain, selector))
            .firstNotNullOfOrNull { text -> parseBimiRecord(text) }
    }

    @Suppress("ReturnCount")
    private fun downloadLogo(logoUrl: String, size: Int): Bitmap? {
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

            val body = response.body
            if (body.contentLength() > MAX_LOGO_BYTES) return null

            val bytes = body.byteStream().readAtMost(MAX_LOGO_BYTES.toInt()) ?: return null

            renderSvg(bytes, size)
        }
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
