package com.fsck.k9.contacts.bimi

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

/**
 * Uses PayPal's real Verified Mark Certificate, which names a genuine distribution point, so the extension
 * parsing is exercised against what an authority actually issues rather than a hand-built fixture.
 */
class CertificateRevocationTest : RobolectricTest() {

    @Test
    fun `a certificate with no reachable list should be unknown`() {
        // Not "not revoked": an unanswered question stays unanswered, so the caller decides what it is worth.
        val checker = CertificateRevocationChecker(fetch = { null })

        assertThat(checker.statusOf(leaf())).isEqualTo(RevocationStatus.UNKNOWN)
    }

    @Test
    fun `a list that does not name the certificate should be not revoked`() {
        val checker = CertificateRevocationChecker(fetch = { emptyCrl() })

        assertThat(checker.statusOf(leaf())).isEqualTo(RevocationStatus.NOT_REVOKED)
    }

    @Test
    fun `unreadable bytes should leave the question unanswered`() {
        val checker = CertificateRevocationChecker(fetch = { byteArrayOf(1, 2, 3) })

        assertThat(checker.statusOf(leaf())).isEqualTo(RevocationStatus.UNKNOWN)
    }

    @Test
    fun `the distribution point should be read from the certificate`() {
        // If the URL cannot be found the check silently never happens, so it is worth asserting directly.
        val requested = mutableListOf<String>()
        val checker = CertificateRevocationChecker(
            fetch = { url ->
                requested.add(url)
                null
            },
        )

        checker.statusOf(leaf())

        assertThat(requested.isNotEmpty()).isEqualTo(true)
        assertThat(requested.all { it.startsWith("http") }).isEqualTo(true)
    }

    /**
     * A syntactically valid, empty CRL signed by nothing in particular. Enough to exercise the "list read,
     * certificate not named" path without needing the authority's key.
     */
    private fun emptyCrl(): ByteArray = readFixture("empty_crl.pem").toByteArray()

    private fun leaf(): X509Certificate {
        val chain = readFixture("paypal_vmc_chain.pem")
        val firstBlock = chain.substringBefore("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----\n"

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(firstBlock.byteInputStream()) as X509Certificate
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Missing fixture: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
