package com.fsck.k9.contacts.bimi

import android.content.Context
import com.fsck.k9.ui.R
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val PEM_END = "-----END CERTIFICATE-----"

/**
 * Loads the pinned Mark Verifying Authority roots.
 *
 * Shipped with the app rather than read from the device's TLS trust store, which answers a different question
 * entirely: a TLS root says a server is who it claims to be, not that an authority checked who owns a
 * trademark. Loading nothing means no brand indicator is shown, which is the safe direction.
 */
fun loadMvaRoots(context: Context): Set<X509Certificate> {
    return runCatching {
        val text = context.resources.openRawResource(R.raw.bimi_mva_roots).use { stream ->
            stream.readBytes().toString(Charsets.US_ASCII)
        }

        parsePemCertificates(text)
    }.getOrDefault(emptySet())
}

/**
 * Reads the PEM blocks out of [text] and parses each on its own.
 *
 * The blocks are cut out explicitly rather than handing the whole file to the certificate factory, because
 * how much surrounding text a factory tolerates is a property of the security provider: the desktop JVM skips
 * the comments in this file and Android's provider returns nothing at all. Relying on that difference meant
 * the roots silently failed to load on device while the tests passed.
 */
internal fun parsePemCertificates(text: String): Set<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")

    return buildSet {
        for (block in pemBlocks(text)) {
            // One unreadable block must not discard the rest: a root that still parses is still a root.
            runCatching { factory.generateCertificate(block.byteInputStream()) }
                .getOrNull()
                ?.let { certificate -> (certificate as? X509Certificate)?.let { add(it) } }
        }
    }
}

private fun pemBlocks(text: String): List<String> {
    return buildList {
        var searchFrom = 0
        var start = text.indexOf(PEM_BEGIN, searchFrom)

        while (start >= 0) {
            val end = text.indexOf(PEM_END, start)
            if (end < 0) break

            add(text.substring(start, end + PEM_END.length))
            searchFrom = end + PEM_END.length
            start = text.indexOf(PEM_BEGIN, searchFrom)
        }
    }
}
