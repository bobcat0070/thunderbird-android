package net.thunderbird.feature.impersonation.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import net.thunderbird.feature.impersonation.Impersonation
import net.thunderbird.feature.impersonation.KnownSenders

class SenderImpersonationTest {
    private val index = KnownSenderIndex(
        KnownSenders(
            addressesByName = mapOf(
                "Alex Reader" to setOf("alex@reader.example"),
                "Jordan Colleague" to setOf("jordan@lawfirm.example", "jordan.c@gmail.com"),
                "Support Team" to setOf("support@vendor.example"),
            ),
            correspondentAddresses = setOf("alex@reader.example", "jordan@lawfirm.example"),
            verifiedSenderDomains = setOf("paypal.com", "github.com", "mail.bigbank.example", "gmail.com"),
        ),
    )

    @Test
    fun `a stranger using the reader's own name from free mail should be flagged`() {
        val result = findImpersonation("Alex Reader", "randomperson123@gmail.com", index)

        assertThat(result).isEqualTo(Impersonation.KnownName("Alex Reader", "randomperson123@gmail.com"))
    }

    @Test
    fun `a known name on an unfamiliar domain should be flagged`() {
        val result = findImpersonation("Jordan Colleague", "jordan@lawfirm-payments.example", index)

        assertThat(result).isEqualTo(Impersonation.KnownName("Jordan Colleague", "jordan@lawfirm-payments.example"))
    }

    @Test
    fun `a known name written with a lookalike letter should still be recognised`() {
        val result = findImpersonation("Jordаn Colleague", "ceo.office@gmail.com", index)

        assertThat(result).isEqualTo(Impersonation.KnownName("Jordаn Colleague", "ceo.office@gmail.com"))
    }

    @Test
    fun `a service sending under a person's name from its own verified domain should not be flagged`() {
        assertThat(findImpersonation("Alex Reader", "notifications@github.com", index)).isNull()
    }

    @Test
    fun `a known person writing from another address at their own organisation should not be flagged`() {
        assertThat(findImpersonation("Jordan Colleague", "j.colleague@lawfirm.example", index)).isNull()
    }

    @Test
    fun `a known person's own addresses should not be flagged`() {
        assertThat(findImpersonation("Jordan Colleague", "Jordan.C@gmail.com", index)).isNull()
    }

    @Test
    fun `generic names should not count as anyone's`() {
        assertThat(findImpersonation("Support Team", "help@phish.example", index)).isNull()
    }

    @Test
    fun `an address in the name at another domain should be flagged`() {
        val result = findImpersonation(
            "counsel@kroll-claims.example via SurveyMonkey",
            "member@surveymonkeyuser.example",
            index,
        )

        assertThat(result).isEqualTo(
            Impersonation.AddressInName("counsel@kroll-claims.example", "surveymonkeyuser.example"),
        )
    }

    @Test
    fun `the sender's own address in the name should not be flagged`() {
        assertThat(findImpersonation("Ann (ann@shop.example)", "ann@mail.shop.example", index)).isNull()
    }

    @Test
    fun `a verified domain named by a sender elsewhere should be flagged`() {
        val result = findImpersonation("PayPal.com", "service@secure-billing.example", index)

        assertThat(result).isEqualTo(Impersonation.DomainInName("paypal.com", "secure-billing.example"))
    }

    @Test
    fun `a brand whose name is its website, mailing from its own mail domain, should not be flagged`() {
        assertThat(findImpersonation("ConsumerLab.com", "news@consumerlabmail.com", index)).isNull()
    }

    @Test
    fun `a domain shaped like a verified one should be flagged`() {
        val result = findImpersonation("PayPal", "service@paypa1.com", index)

        assertThat(result).isEqualTo(Impersonation.LookalikeDomain("paypa1.com", "paypal.com"))
    }

    @Test
    fun `a domain using another alphabet to look like a verified one should be flagged`() {
        val result = findImpersonation("PayPal", "service@xn--pypal-4ve.com", index)

        assertThat(result).isEqualTo(Impersonation.LookalikeDomain("pаypal.com", "paypal.com"))
    }

    @Test
    fun `a lookalike of a correspondent's domain should be flagged`() {
        val result = findImpersonation("Accounts", "billing@lawflrm.example", index)

        assertThat(result).isEqualTo(Impersonation.LookalikeDomain("lawflrm.example", "lawfirm.example"))
    }

    @Test
    fun `the real domain and its subdomains should not be flagged`() {
        assertThat(findImpersonation("PayPal", "service@paypal.com", index)).isNull()
        assertThat(findImpersonation("PayPal", "service@mail.paypal.com", index)).isNull()
    }

    @Test
    fun `a domain mixing alphabets should be flagged even when it imitates nothing known`() {
        val result = findImpersonation("Shop", "sales@xn--shp-ted.example", index)

        assertThat(result).isEqualTo(Impersonation.MixedScriptDomain("shоp.example"))
    }

    @Test
    fun `ordinary unknown senders should not be flagged`() {
        assertThat(findImpersonation("Newsletter", "news@unknown-shop.example", index)).isNull()
        assertThat(findImpersonation(null, "someone@unknown.example", index)).isNull()
        assertThat(findImpersonation("Name", null, index)).isNull()
    }
}
