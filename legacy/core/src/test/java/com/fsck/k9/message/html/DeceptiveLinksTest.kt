package com.fsck.k9.message.html

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test

class DeceptiveLinksTest {
    @Test
    fun `a link naming one site over an address at another should be found`() {
        val html = """<a href="https://paypal.com.account-check.example/login">https://www.paypal.com</a>"""

        assertThat(findDeceptiveLinks(html)).isEqualTo(mapOf("paypal.com.account-check.example" to "paypal.com"))
    }

    @Test
    fun `a link going where its text says should not be found`() {
        val html = """<a href="https://www.paypal.com/signin?x=1">paypal.com</a>"""

        assertThat(findDeceptiveLinks(html)).isEmpty()
    }

    @Test
    fun `a subdomain of the named site should count as the same site`() {
        // How a company's own mail usually links to itself.
        val html = """<a href="https://click.e.amazon.com/track?id=1">amazon.com</a>"""

        assertThat(findDeceptiveLinks(html)).isEmpty()
    }

    @Test
    fun `link text that is not an address should not be judged`() {
        // A newsletter's "Shop now" over a tracking address claims nothing about where it goes.
        val html = """<a href="https://tracking.example.net/r/123">Shop now at shop.example</a>"""

        assertThat(findDeceptiveLinks(html)).isEmpty()
    }

    @Test
    fun `a lookalike in another alphabet should not pass for the real site`() {
        // The "а" here is Cyrillic.
        val html = """<a href="https://xn--pypal-4ve.com/">pаypal.com</a>"""

        assertThat(findDeceptiveLinks(html)).isEmpty()
        val spoof = """<a href="https://xn--pypal-4ve.com/">paypal.com</a>"""
        assertThat(findDeceptiveLinks(spoof)).isEqualTo(mapOf("xn--pypal-4ve.com" to "paypal.com"))
    }

    @Test
    fun `links that are not to websites should be ignored`() {
        val html = """<a href="mailto:help@evil.example">help@paypal.com</a><a href="tel:123">paypal.com</a>"""

        assertThat(findDeceptiveLinks(html)).isEmpty()
    }

    @Test
    fun `the host of a link should ignore credentials and the leading www`() {
        assertThat(hostOf("https://user:pass@WWW.Example.COM:8443/path")).isEqualTo("example.com")
    }
}
