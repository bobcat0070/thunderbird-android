package com.fsck.k9.contacts.bimi

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.security.MessageDigest
import java.security.cert.X509Certificate
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

/**
 * Goes through the real resource and the real loader rather than parsing the file directly.
 *
 * An earlier version of this test used the desktop JVM's certificate factory on the same bytes and passed
 * while the roots silently failed to load on device: how much surrounding text a factory tolerates turns out
 * to be a property of the security provider. Since empty roots mean every certificate is rejected and the
 * feature quietly stops working with no error anywhere, the test has to exercise the path the app uses.
 */
class MvaRootsTest : RobolectricTest() {

    @Test
    fun `every bundled root should load`() {
        val roots = loadMvaRoots(ApplicationProvider.getApplicationContext())

        assertThat(roots).hasSize(EXPECTED_ROOTS.size)
    }

    @Test
    fun `the bundled roots should be exactly the expected authorities`() {
        // Pinning the fingerprints means a swapped or added root fails the build rather than silently
        // becoming an authority the app will believe about who owns a trademark.
        val roots = loadMvaRoots(ApplicationProvider.getApplicationContext())

        assertThat(roots.map { it.sha256Fingerprint() }.toSet()).isEqualTo(EXPECTED_ROOTS)
    }

    @Test
    fun `every bundled root should be self-signed`() {
        // A trust anchor is a root. An intermediate here would mean trusting a link in someone else's chain.
        val roots = loadMvaRoots(ApplicationProvider.getApplicationContext())

        assertThat(roots.all { it.subjectX500Principal == it.issuerX500Principal }).isTrue()
    }

    @Test
    fun `comments around a certificate should not stop it loading`() {
        // The provenance notes in the bundled file are exactly this shape.
        val text = "# where this came from\n# and how it was checked\n" + pemBlock()

        assertThat(parsePemCertificates(text)).hasSize(1)
    }

    @Test
    fun `several certificates in one file should all load`() {
        val text = pemBlock() + "\n# another authority\n" + pemBlock()

        // The same certificate twice collapses to one, which is the point of returning a set.
        assertThat(parsePemCertificates(text)).hasSize(1)
    }

    @Test
    fun `an unreadable block should not discard the readable ones`() {
        val text = "-----BEGIN CERTIFICATE-----\nnot base64 at all\n-----END CERTIFICATE-----\n" + pemBlock()

        assertThat(parsePemCertificates(text)).hasSize(1)
    }

    @Test
    fun `a file with no certificates should load nothing`() {
        assertThat(parsePemCertificates("# nothing here")).isEmpty()
    }

    private fun pemBlock(): String {
        val encoded = loadMvaRoots(ApplicationProvider.getApplicationContext()).first().encoded
        val base64 = android.util.Base64.encodeToString(encoded, android.util.Base64.NO_WRAP)

        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
    }

    private fun X509Certificate.sha256Fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02X".format(it) }

    private companion object {
        /**
         * The Mark Verifying Authorities the AuthIndicators Working Group lists, plus GoDaddy.
         */
        val EXPECTED_ROOTS = setOf(
            // DigiCert Verified Mark Root CA
            "504386C9EE8932FECC95FADE427F69C3E2534B7310489E300FEE448E33C46B42",
            // Entrust Verified Mark Root Certification Authority - VMCR1
            "7831D95A47D42508CD5C9E6264F9096BAC19F04EB9B7C8BDD35FFFC71C189617",
            // SSL.com VMC ECC Root CA 2024
            "1B82E7F4910B51E3E802A493ACDC17FF58EAC8B9EB7C09B52AC6CD2EFB83598C",
            // SSL.com VMC RSA Root CA 2024
            "8F9D1B7698886782A599B48510651C66A1AA0C5CA3192097BDC68534154BD30D",
            // GlobalSign Verified Mark Root R42
            "CD122CB877C6928B9017B0F0B80DBD508196300BBD03CD7356C3BEEF524E7E0B",
            // GoDaddy Verified Mark Root CA - VMCR1
            "6AE4F0B06B187A7FC2B5815D252B637629734AE64A409CCBB53B14A352563D61",
        )
    }
}
