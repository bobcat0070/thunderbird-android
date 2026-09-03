package com.fsck.k9.mailstore

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class SenderAuthenticationTest {

    @Test
    fun `dmarc pass should count as authenticated`() {
        val header = "spf=pass (sender IP is 1.2.3.4) smtp.mailfrom=example.com; dkim=pass " +
            "header.d=example.com;dmarc=pass action=none header.from=example.com;compauth=pass reason=100"

        assertThat(hasDmarcPass(listOf(header))).isTrue()
    }

    @Test
    fun `dmarc bestguesspass should not count as authenticated`() {
        // Microsoft's guess for domains that publish no DMARC record at all. Treating a guess as a pass is
        // exactly how a brand indicator turns into a phishing aid.
        val header = "spf=pass smtp.mailfrom=example.com; dkim=pass header.d=example.com;" +
            "dmarc=bestguesspass action=none header.from=example.com"

        assertThat(hasDmarcPass(listOf(header))).isFalse()
    }

    @Test
    fun `dmarc fail should not count as authenticated`() {
        assertThat(hasDmarcPass(listOf("spf=fail; dmarc=fail action=oreject header.from=example.com"))).isFalse()
    }

    @Test
    fun `dmarc none should not count as authenticated`() {
        assertThat(hasDmarcPass(listOf("dmarc=none action=none header.from=example.com"))).isFalse()
    }

    @Test
    fun `spf and dkim passing without dmarc should not count`() {
        // Only DMARC ties the From domain the user sees to a sender the domain authorised.
        assertThat(hasDmarcPass(listOf("spf=pass smtp.mailfrom=bounce.example.net; dkim=pass"))).isFalse()
    }

    @Test
    fun `a pass on any of several headers should count`() {
        // A message collects one header per hop.
        val headers = listOf(
            "i=2; mx.microsoft.com 1; spf=pass; dmarc=pass (p=reject sp=none pct=100) header.from=example.com",
            "i=1; dkim=none",
        )

        assertThat(hasDmarcPass(headers)).isTrue()
    }

    @Test
    fun `result should be matched regardless of case`() {
        assertThat(hasDmarcPass(listOf("DMARC=PASS action=none"))).isTrue()
    }

    @Test
    fun `a value that merely starts with pass should not count`() {
        // "passed" and "passing" are not DMARC results, and a prefix match would accept anything.
        assertThat(hasDmarcPass(listOf("dmarc=passx action=none"))).isFalse()
    }

    @Test
    fun `no headers should not count as authenticated`() {
        assertThat(hasDmarcPass(emptyList())).isFalse()
    }
}
