package com.fsck.k9.contacts.bimi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.security.cert.CertificateFactory
import java.util.Date
import java.security.cert.X509Certificate
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

/**
 * Uses PayPal's published Verified Mark Certificate, which is real and therefore exercises the parts a
 * hand-written fixture would quietly get wrong: a genuine DigiCert chain, the BIMI extended key usage, real
 * subject alternative names, and a gzipped mark inside the logotype extension.
 */
class VmcValidatorTest : RobolectricTest() {

    @Test
    fun `a genuine certificate should verify for a domain it names`() {
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(chain().byteInputStream(), "paypal.com")

        assertThat(mark).isNotNull()
    }

    @Test
    fun `a genuine certificate should not verify for a domain it does not name`() {
        // The attack this whole feature has to stop: a lookalike domain passes its own DMARC and points at a
        // real brand's certificate, hoping to borrow the logo.
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(chain().byteInputStream(), "paypa1.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `a certificate should not verify against an authority that did not issue it`() {
        // A chain is never trusted because it arrived with its own root attached.
        val validator = VmcValidator(trustAnchors = setOf(unrelatedRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(chain().byteInputStream(), "paypal.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `no trust anchors should verify nothing`() {
        // If the pinned roots could not be loaded, nothing is vouched for and nothing may be shown.
        val validator = VmcValidator(trustAnchors = emptySet(), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(chain().byteInputStream(), "paypal.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `a chain with only the leaf should not verify`() {
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(leafOnly().byteInputStream(), "paypal.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `text that is not a certificate should not verify`() {
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate("not a certificate".byteInputStream(), "paypal.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `a certificate attesting a registered trademark should be the verified tier`() {
        // Only this tier earns the check the specification defines.
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(chain().byteInputStream(), "paypal.com")

        assertThat(mark?.assurance).isEqualTo(MarkAssurance.VERIFIED)
    }

    @Test
    fun `an expired certificate should not verify`() {
        // A mark authority vouches for a period, not forever.
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(AFTER_EXPIRY) })

        val mark = validator.validate(chain().byteInputStream(), "paypal.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `a certificate is not yet valid before its start date should not verify`() {
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(BEFORE_ISSUE) })

        val mark = validator.validate(chain().byteInputStream(), "paypal.com")

        assertThat(mark).isNull()
    }

    @Test
    fun `domain matching should ignore case`() {
        val validator = VmcValidator(trustAnchors = setOf(digicertRoot()), now = { Date(WITHIN_VALIDITY) })

        val mark = validator.validate(chain().byteInputStream(), "PayPal.COM")

        assertThat(mark).isNotNull()
    }

    private companion object {
        /**
         * The fixture is valid from 13 Jul 2026 to 12 Jul 2027, so the tests pin a date inside that window
         * rather than depending on when they happen to run.
         */
        const val WITHIN_VALIDITY = 1_790_000_000_000L
        const val BEFORE_ISSUE = 1_700_000_000_000L
        const val AFTER_EXPIRY = 1_900_000_000_000L
    }

    private fun chain(): String = readFixture("paypal_vmc_chain.pem")

    private fun leafOnly(): String = chain().split("-----END CERTIFICATE-----")
        .first() + "-----END CERTIFICATE-----\n"

    private fun digicertRoot(): X509Certificate = readCertificate(readFixture("digicert_vmc_root.pem"))

    /**
     * Any root that did not issue the chain works for the negative case; the app's own resource is a
     * convenient one that is definitely not a DigiCert mark authority.
     */
    private fun unrelatedRoot(): X509Certificate = readCertificate(readFixture("unrelated_root.pem"))

    private fun readCertificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Missing fixture: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
