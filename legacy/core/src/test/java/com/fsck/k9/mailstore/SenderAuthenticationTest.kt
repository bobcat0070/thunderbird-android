package com.fsck.k9.mailstore

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
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

class SenderAuthenticationOutcomesTest {

    @Test
    fun `a fully aligned message should show every check passing`() {
        val header = "spf=pass (sender IP is 1.2.3.4) smtp.mailfrom=example.com; " +
            "dkim=pass (signature was verified) header.d=example.com;" +
            "dmarc=pass action=none header.from=example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = true),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = true),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = true),
        )
    }

    @Test
    fun `a sending subdomain should still count as aligned`() {
        // What almost all bulk mail looks like: the envelope sender is a bounce address at a subdomain of the
        // brand, and the signature is the brand itself.
        val header = "spf=pass smtp.mailfrom=bounce@mail.example.com; dkim=pass header.d=example.com; " +
            "dmarc=pass header.from=example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = true),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = true),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = true),
        )
    }

    @Test
    fun `an spf pass for an unrelated domain should not count`() {
        // The whole point of the alignment check. Anyone can send mail that passes SPF for a domain they own
        // while writing somebody else's address in From; without this the reader would see a tick.
        val header = "spf=pass smtp.mailfrom=bounces@mailer.attacker.example; " +
            "dkim=fail header.d=example.com; dmarc=fail header.from=example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = false),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = false),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = false),
        )
    }

    @Test
    fun `one aligned signature among several should be enough`() {
        // A forwarding list often adds its own signature beside the original one.
        val header = "dkim=pass header.d=list.other.example; dkim=pass header.d=example.com; " +
            "spf=fail smtp.mailfrom=list.other.example; dmarc=pass header.from=example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = true),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = false),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = true),
        )
    }

    @Test
    fun `a comment carrying a policy should not be read as a result`() {
        // Google writes "(p=REJECT sp=REJECT dis=NONE)" into its DMARC comment, and its SPF comment names the
        // sending domain. Either would be misread by anything splitting the header without stripping comments.
        val header = "mx.google.com; dkim=pass header.i=@example.com header.s=s1 header.b=aBc; " +
            "spf=pass (google.com: domain of bounce@example.com designates 1.2.3.4 as permitted sender) " +
            "smtp.mailfrom=bounce@example.com; dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = true),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = true),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = true),
        )
    }

    @Test
    fun `a dmarc pass judging a different From domain should not count`() {
        // An upstream hop's verdict about somebody else's message, still attached when it reached this
        // mailbox. It says nothing about the sender the reader is looking at.
        val header = "dmarc=pass header.from=other.example"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = false),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = false),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = false),
        )
    }

    @Test
    fun `a mechanism nobody reported on should not be shown as passing`() {
        val header = "spf=pass smtp.mailfrom=example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = false),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = true),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = false),
        )
    }

    @Test
    fun `a message no server reported on should say nothing at all`() {
        // Not the same as everything failing, and drawing three struck-through checks would claim it was.
        assertThat(authenticationOutcomes(emptyList(), "example.com")).isEmpty()
    }

    @Test
    fun `a header with nothing but the server name should say nothing at all`() {
        assertThat(authenticationOutcomes(listOf("mx.google.com"), "example.com")).isEmpty()
    }

    @Test
    fun `a message with no sender domain should pass nothing`() {
        val header = "spf=pass smtp.mailfrom=example.com; dkim=pass header.d=example.com; dmarc=pass"

        // DMARC still counts: the server made the comparison and named no other From domain.
        assertThat(authenticationOutcomes(listOf(header), fromDomain = null)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = false),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = false),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = true),
        )
    }

    @Test
    fun `results should be read whatever case the server wrote them in`() {
        val header = "SPF=Pass smtp.mailfrom=Bounce@Example.COM; DKIM=Pass header.d=EXAMPLE.com; " +
            "DMARC=Pass header.from=Example.com"

        assertThat(outcomes(header)).containsExactly(
            AuthenticationOutcome(AuthenticationMethod.DKIM, passed = true),
            AuthenticationOutcome(AuthenticationMethod.SPF, passed = true),
            AuthenticationOutcome(AuthenticationMethod.DMARC, passed = true),
        )
    }

    private fun outcomes(header: String) = authenticationOutcomes(listOf(header), fromDomain = "example.com")
}
