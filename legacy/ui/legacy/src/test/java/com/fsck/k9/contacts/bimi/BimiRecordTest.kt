package com.fsck.k9.contacts.bimi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class BimiRecordTest {

    @Test
    fun `should parse a record`() {
        val record = parseBimiRecord("v=BIMI1; l=https://example.com/logo.svg; a=https://example.com/vmc.pem")

        assertThat(record?.logoUrl).isEqualTo("https://example.com/logo.svg")
        assertThat(record?.authorityUrl).isEqualTo("https://example.com/vmc.pem")
    }

    @Test
    fun `should parse a record without a certificate`() {
        val record = parseBimiRecord("v=BIMI1; l=https://example.com/logo.svg")

        assertThat(record?.logoUrl).isEqualTo("https://example.com/logo.svg")
        assertThat(record?.authorityUrl).isNull()
    }

    @Test
    fun `version should be matched regardless of case`() {
        val record = parseBimiRecord("v=bimi1; l=https://example.com/logo.svg")

        assertThat(record?.logoUrl).isEqualTo("https://example.com/logo.svg")
    }

    @Test
    fun `a record for another protocol should be rejected`() {
        // A name can hold TXT records for several things; only a BIMI record may be read as one.
        assertThat(parseBimiRecord("v=spf1 include:example.com ~all")).isNull()
        assertThat(parseBimiRecord("v=DMARC1; p=reject")).isNull()
    }

    @Test
    fun `a plain http logo should be rejected`() {
        // A logo that could be swapped in transit is the opposite of an assurance.
        assertThat(parseBimiRecord("v=BIMI1; l=http://example.com/logo.svg")).isNull()
    }

    @Test
    fun `a record declaring no indicator should be rejected`() {
        // An empty location is a domain saying it deliberately has no logo. Valid, and still nothing to show.
        assertThat(parseBimiRecord("v=BIMI1; l=")).isNull()
    }

    @Test
    fun `a plain http certificate should not be carried`() {
        val record = parseBimiRecord("v=BIMI1; l=https://example.com/logo.svg; a=http://example.com/vmc.pem")

        assertThat(record?.authorityUrl).isNull()
    }

    @Test
    fun `should build the record name for the default selector`() {
        assertThat(bimiRecordName("example.com")).isEqualTo("default._bimi.example.com")
    }

    @Test
    fun `should build the record name for a named selector`() {
        assertThat(bimiRecordName("example.com", "marketing")).isEqualTo("marketing._bimi.example.com")
    }

    @Test
    fun `should read the selector a message asks for`() {
        assertThat(parseBimiSelector("v=BIMI1; s=marketing")).isEqualTo("marketing")
    }

    @Test
    fun `a missing selector header should mean the default selector`() {
        assertThat(parseBimiSelector(null)).isEqualTo(BIMI_DEFAULT_SELECTOR)
        assertThat(parseBimiSelector("v=BIMI1")).isEqualTo(BIMI_DEFAULT_SELECTOR)
    }

    @Test
    fun `a selector that is not a valid DNS label should be ignored`() {
        // The selector becomes part of a DNS name, so it is restricted rather than trusted.
        assertThat(parseBimiSelector("v=BIMI1; s=evil.example.com")).isEqualTo(BIMI_DEFAULT_SELECTOR)
        assertThat(parseBimiSelector("v=BIMI1; s=with space")).isEqualTo(BIMI_DEFAULT_SELECTOR)
        assertThat(parseBimiSelector("v=BIMI1; s=")).isEqualTo(BIMI_DEFAULT_SELECTOR)
    }
}
