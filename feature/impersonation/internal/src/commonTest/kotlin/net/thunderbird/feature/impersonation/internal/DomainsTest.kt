package net.thunderbird.feature.impersonation.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

class DomainsTest {
    @Test
    fun `decodePunycodeLabel should decode labels written in other alphabets`() {
        assertThat(decodePunycodeLabel("xn--pypal-4ve")).isEqualTo("pаypal")
        assertThat(decodePunycodeLabel("xn--mnchen-3ya")).isEqualTo("münchen")
    }

    @Test
    fun `decodePunycodeLabel should leave plain labels alone and refuse broken ones`() {
        assertThat(decodePunycodeLabel("example")).isEqualTo("example")
        assertThat(decodePunycodeLabel("xn--pypal-4v!")).isNull()
    }

    @Test
    fun `registrableDomain should drop subdomains and keep country code second levels`() {
        assertThat(registrableDomain("mail.Example.com.")).isEqualTo("example.com")
        assertThat(registrableDomain("news.bbc.co.uk")).isEqualTo("bbc.co.uk")
        assertThat(registrableDomain("example.com")).isEqualTo("example.com")
    }

    @Test
    fun `skeleton should fold the usual lookalike tricks together`() {
        val paypal = skeleton("paypal")

        assertThat(skeleton("paypa1")).isEqualTo(paypal)
        assertThat(skeleton("pаypal")).isEqualTo(paypal)
        assertThat(skeleton("pay-pal")).isEqualTo(paypal)
        assertThat(skeleton("paypall")).isEqualTo(paypal)
        assertThat(skeleton("rnicrosoft")).isEqualTo(skeleton("microsoft"))
        assertThat(skeleton("micros0ft")).isEqualTo(skeleton("microsoft"))
    }

    @Test
    fun `skeleton should keep different names apart`() {
        assertThat(skeleton("paypal")).isNotEqualTo(skeleton("payoneer"))
        assertThat(skeleton("amazon")).isNotEqualTo(skeleton("amazing"))
    }

    @Test
    fun `hasMixedScriptLabel should spot Latin and Cyrillic in one name`() {
        assertThat(hasMixedScriptLabel("pаypal.com")).isTrue()
        assertThat(hasMixedScriptLabel("пример.рф")).isFalse()
        assertThat(hasMixedScriptLabel("münchen.de")).isFalse()
    }
}
