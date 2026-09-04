package com.fsck.k9.contacts.bimi

import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate

/**
 * CRL distribution points, where a certificate says which list would name it if it were revoked.
 */
private const val CRL_DISTRIBUTION_POINTS_OID = "2.5.29.31"

/**
 * The context-specific tag a distribution point URI is encoded with, rather than a plain IA5String.
 */
private const val TAG_URI = 0x86

/**
 * What could be established about a certificate's revocation.
 */
enum class RevocationStatus {
    /** The list was read and does not name this certificate. */
    NOT_REVOKED,

    /** The list names this certificate. */
    REVOKED,

    /**
     * No answer: no distribution point, or the list could not be fetched or read.
     *
     * Kept distinct from [NOT_REVOKED] so the caller decides what an unanswered question is worth, rather
     * than having "we could not check" quietly become "it is fine".
     */
    UNKNOWN,
}

/**
 * Checks whether a mark certificate has been revoked.
 *
 * Done here rather than by turning on PKIX revocation checking, because that would make every path
 * validation block on a network fetch with no cache and no say in what happens when it fails. A revocation
 * list is small, changes slowly, and is shared by every certificate an authority issues, so fetching it once
 * and reusing it is both cheaper and more predictable than asking per certificate.
 *
 * @param fetch retrieves the bytes at a URL, or returns `null` when they cannot be had.
 */
class CertificateRevocationChecker(private val fetch: (String) -> ByteArray?) {

    @Suppress("ReturnCount")
    fun statusOf(certificate: X509Certificate): RevocationStatus {
        val urls = distributionPointUrls(certificate)
        if (urls.isEmpty()) return RevocationStatus.UNKNOWN

        var sawAList = false

        for (url in urls) {
            val crl = crlAt(url) ?: continue
            sawAList = true

            if (crl.isRevoked(certificate)) return RevocationStatus.REVOKED
        }

        return if (sawAList) RevocationStatus.NOT_REVOKED else RevocationStatus.UNKNOWN
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun crlAt(url: String): X509CRL? {
        return try {
            val bytes = fetch(url) ?: return null

            CertificateFactory.getInstance("X.509").generateCRL(bytes.inputStream()) as? X509CRL
        } catch (e: Exception) {
            // An unreachable or unparsable list leaves the question unanswered, which the caller handles.
            null
        }
    }

    /**
     * Reads the http URLs out of the distribution points extension.
     *
     * The extension is walked for URI-tagged strings rather than decoded against the full grammar: the only
     * thing needed is where the list lives, and the alternatives to a URL in that extension are forms this
     * cannot fetch anyway.
     */
    @Suppress("ReturnCount")
    private fun distributionPointUrls(certificate: X509Certificate): List<String> {
        val extension = certificate.getExtensionValue(CRL_DISTRIBUTION_POINTS_OID) ?: return emptyList()
        val contents = unwrapOctetString(extension) ?: return emptyList()

        return collectTaggedStrings(contents, TAG_URI).filter { it.startsWith("http", ignoreCase = true) }
    }
}
