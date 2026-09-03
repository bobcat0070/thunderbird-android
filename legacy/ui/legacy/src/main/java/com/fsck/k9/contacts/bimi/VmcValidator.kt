package com.fsck.k9.contacts.bimi

import java.io.InputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Extended key usage that marks a certificate as a Brand Indicator for Message Identification certificate.
 *
 * Without it a certificate is some other kind of certificate the organisation happens to hold, which says
 * nothing about a verified mark.
 */
private const val BIMI_EXTENDED_KEY_USAGE = "1.3.6.1.5.5.7.3.31"

/**
 * The `dNSName` form inside a subjectAltName, per the numbering in RFC 5280.
 */
private const val SAN_TYPE_DNS_NAME = 2

/**
 * Hex characters per byte, when reading the RFC 2253 form of an attribute value.
 */
private const val HEX_PER_BYTE = 2

/**
 * The tag and length that precede the string in a DER-encoded attribute value.
 */
private const val DER_HEADER_BYTES = 2

/**
 * Certificate policy asserting a Verified Mark Certificate, which attests a registered trademark.
 */
private const val VMC_POLICY_OID = "1.3.6.1.4.1.53087.1.1"

/**
 * Subject attribute naming what kind of mark was verified.
 */
private const val MARK_TYPE_OID = "1.3.6.1.4.1.53087.1.13"

private const val REGISTERED_MARK = "registered mark"

/**
 * How much an authority is willing to say about a mark.
 */
enum class MarkAssurance {
    /**
     * A Verified Mark Certificate: an authority checked that this organisation owns this registered
     * trademark. The only tier that earns the check the specification defines.
     */
    VERIFIED,

    /**
     * A Common Mark Certificate: an authority checked something weaker than trademark registration, such as
     * prior use. Shown plainly, with nothing implying it was verified as a trademark.
     */
    COMMON,
}

/**
 * A mark a Mark Verifying Authority has vouched for.
 *
 * @param svg the logo taken from the certificate itself, so what is displayed is what was attested.
 */
data class VerifiedMark(val svg: ByteArray, val assurance: MarkAssurance) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is VerifiedMark && assurance == other.assurance && svg.contentEquals(other.svg))

    override fun hashCode(): Int = 31 * svg.contentHashCode() + assurance.hashCode()
}

/**
 * Checks that a Verified Mark Certificate really vouches for a domain's logo.
 *
 * This is what makes a brand indicator mean anything. DMARC only shows that a domain authorised its own mail;
 * it says nothing about whether the domain may use the mark it is displaying. Any domain passing its own
 * DMARC could otherwise publish a bank's logo, which would make the feature a phishing aid rather than a
 * defence. The certificate is the part where an authority states that this organisation owns this trademark.
 *
 * @param trustAnchors the Mark Verifying Authority roots. Pinned deliberately: the device's TLS trust store
 *   answers a different question, and a chain is never trusted because it supplied its own root.
 */
class VmcValidator(
    private val trustAnchors: Set<X509Certificate>,
    /**
     * When the chain is judged valid. Injectable so expiry can be tested without waiting for a certificate to
     * expire, and so a test using a real certificate does not start failing on the day it does.
     */
    private val now: () -> Date = { Date() },
    /**
     * Reports why a certificate was rejected. A silent rejection is indistinguishable from a domain that
     * publishes no mark, which makes a misconfiguration impossible to tell from correct behaviour.
     */
    private val onRejected: (String) -> Unit = {},
    /**
     * Consulted after the path validates. Absent means revocation is not checked at all, which is only ever
     * the right answer in a test.
     */
    private val revocationChecker: CertificateRevocationChecker? = null,
) {

    @Suppress("ReturnCount")
    fun validate(chainPem: InputStream, senderDomain: String): VerifiedMark? {
        if (trustAnchors.isEmpty()) return reject("no pinned mark authorities were loaded")

        val chain = runCatching { readChain(chainPem) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return reject("chain could not be read")
        val leaf = chain.first()

        chainValidationFailure(chain)?.let { return reject("chain does not validate: $it") }
        if (!hasBimiExtendedKeyUsage(leaf)) return reject("certificate lacks the BIMI extended key usage")
        if (!coversDomain(leaf, senderDomain)) return reject("certificate does not name $senderDomain")
        if (isRevoked(leaf)) return reject("certificate has been revoked")

        val svg = leaf.getExtensionValue(LOGOTYPE_EXTENSION_OID)
            ?.let { unwrapOctetString(it) }
            ?.let { extractLogotypeSvg(it) }
            ?: return reject("certificate carries no usable mark")

        return VerifiedMark(svg, leaf.assurance())
    }

    private fun readChain(chainPem: InputStream): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")

        return factory.generateCertificates(chainPem).filterIsInstance<X509Certificate>()
    }

    /**
     * Validates the path against the pinned roots.
     *
     * Any self-signed certificate the sender included is dropped first: a path may not contain its own trust
     * anchor, and more to the point, a root is trusted because it is pinned here and never because it arrived
     * alongside the certificate it is supposed to vouch for.
     */
    private fun reject(reason: String): VerifiedMark? {
        onRejected(reason)

        return null
    }

    /**
     * @return why the path is not acceptable, or `null` when it is.
     */
    private fun chainValidationFailure(chain: List<X509Certificate>): String? {
        val path = chain.filterNot { it.issuerX500Principal == it.subjectX500Principal }
        if (path.isEmpty()) return "chain contained nothing but self-signed certificates"

        return try {
            val certPath = CertificateFactory.getInstance("X.509").generateCertPath(path)
            val parameters = PKIXParameters(trustAnchors.map { TrustAnchor(it, null) }.toSet()).apply {
                // Signatures, issuer chaining and validity are all checked; revocation is not, because a
                // device cannot rely on reaching an OCSP responder while drawing a list row. A revoked mark
                // therefore keeps showing until it expires, which is the one weaker claim made here.
                isRevocationEnabled = false
                date = now()
            }

            CertPathValidator.getInstance("PKIX").validate(certPath, parameters)
            null
        } catch (e: GeneralSecurityException) {
            e.message ?: e::class.java.simpleName
        }
    }

    /**
     * Only a certificate that says it attests a registered trademark gets the verified tier. Anything else an
     * authority is willing to sign - prior use, a government mark - is a weaker statement and is shown as
     * such, so being strict here is the direction that cannot overclaim.
     */
    private fun X509Certificate.assurance(): MarkAssurance {
        val policies = runCatching { getExtensionValue("2.5.29.32") }.getOrNull()
        val hasVmcPolicy = policies != null && String(policies, Charsets.ISO_8859_1).contains(VMC_POLICY_OID)
        val isRegisteredMark = subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
            .contains(MARK_TYPE_OID, ignoreCase = true) &&
            markTypeValue().equals(REGISTERED_MARK, ignoreCase = true)

        return if (hasVmcPolicy || isRegisteredMark) MarkAssurance.VERIFIED else MarkAssurance.COMMON
    }

    /**
     * The mark type is a subject attribute the certificate factory does not name, so it is read out of the
     * RFC 2253 form of the subject, where it appears as its OID followed by a DER-encoded printable string.
     */
    private fun X509Certificate.markTypeValue(): String {
        val name = subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
        val marker = "$MARK_TYPE_OID=#"
        val start = name.indexOf(marker, ignoreCase = true).takeIf { it >= 0 } ?: return ""
        val hex = name.substring(start + marker.length).substringBefore(',').trim()

        return runCatching { decodeDerString(hex) }.getOrDefault("")
    }

    /**
     * A mark that has been withdrawn must stop being shown, so a certificate the list names is refused.
     *
     * An unanswered check is not treated as revocation: a device that cannot reach the list would otherwise
     * lose every brand indicator the moment it went offline, which punishes the user for a network problem
     * rather than protecting them from anything.
     */
    private fun isRevoked(leaf: X509Certificate): Boolean =
        revocationChecker?.statusOf(leaf) == RevocationStatus.REVOKED

    private fun hasBimiExtendedKeyUsage(leaf: X509Certificate): Boolean =
        runCatching { leaf.extendedKeyUsage }.getOrNull()?.contains(BIMI_EXTENDED_KEY_USAGE) == true

    /**
     * The certificate must name the domain the BIMI record was found at, otherwise one organisation's
     * verified mark could be replayed by any other domain that simply points at it.
     */
    private fun coversDomain(leaf: X509Certificate, senderDomain: String): Boolean {
        val domain = senderDomain.trim().lowercase()
        val names = runCatching { leaf.subjectAlternativeNames }.getOrNull().orEmpty()

        return names.any { entry ->
            entry.size >= 2 &&
                entry[0] == SAN_TYPE_DNS_NAME &&
                (entry[1] as? String)?.trim()?.lowercase() == domain
        }
    }

}

/**
 * Decodes the hex-encoded DER value RFC 2253 uses for attribute types it has no name for. The first two bytes
 * are the tag and length; the rest is the string.
 */
private fun decodeDerString(hex: String): String {
    val bytes = ByteArray(hex.length / HEX_PER_BYTE) { index ->
        hex.substring(index * HEX_PER_BYTE, index * HEX_PER_BYTE + HEX_PER_BYTE).toInt(16).toByte()
    }

    if (bytes.size <= DER_HEADER_BYTES) return ""

    return String(bytes, DER_HEADER_BYTES, bytes.size - DER_HEADER_BYTES, Charsets.UTF_8)
}

private typealias GeneralSecurityException = java.security.GeneralSecurityException
