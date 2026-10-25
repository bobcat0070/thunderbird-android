package com.fsck.k9.message.html

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.net.URLEncoder
import kotlin.test.Test

class TrackingLinksTest {
    @Test
    fun `campaign and click parameters should be removed and the rest kept as written`() {
        val result = stripTracking(
            "https://shop.example/item?id=42&utm_source=newsletter&utm_medium=email&color=dark%20blue&fbclid=abc",
        )

        assertThat(result).isEqualTo(
            StrippedLink(
                url = "https://shop.example/item?id=42&color=dark%20blue",
                removedParameters = listOf("utm_source", "utm_medium", "fbclid"),
                skippedRedirects = emptyList(),
            ),
        )
    }

    @Test
    fun `a query left empty should be dropped and the fragment kept`() {
        val result = stripTracking("https://news.example/story?utm_campaign=fall&mc_eid=123#comments")

        assertThat(result?.url).isEqualTo("https://news.example/story#comments")
    }

    @Test
    fun `tracking parameter names should match in any case`() {
        assertThat(stripTracking("https://a.example/?UTM_Source=x&GCLID=y")?.url).isEqualTo("https://a.example/")
    }

    @Test
    fun `a link with nothing to strip should give nothing`() {
        assertThat(stripTracking("https://www.example.com/path?ref=home&source=menu")).isNull()
        assertThat(stripTracking("https://www.example.com/")).isNull()
    }

    @Test
    fun `links that are not web links should give nothing`() {
        assertThat(stripTracking("mailto:someone@example.com?subject=utm_source")).isNull()
        assertThat(stripTracking("tel:+15551234")).isNull()
    }

    @Test
    fun `a Microsoft Safe Links wrapper should be skipped along with the address it carries`() {
        val result = stripTracking(
            "https://nam12.safelinks.protection.outlook.com/?url=" +
                "https%3A%2F%2Fshop.example%2Fsale%3Futm_source%3Dmail%26id%3D7" +
                "&data=05%7C02%7Cfelix%40example.com%7C&sdata=abc&reserved=0",
        )

        assertThat(result).isEqualTo(
            StrippedLink(
                url = "https://shop.example/sale?id=7",
                removedParameters = listOf("utm_source"),
                skippedRedirects = listOf("nam12.safelinks.protection.outlook.com"),
            ),
        )
    }

    @Test
    fun `nested redirectors should all be skipped`() {
        val google = "https://www.google.com/url?q=https%3A%2F%2Fsite.example%2Fpage&sa=D"
        val safeLinks = "https://eur01.safelinks.protection.outlook.com/?url=" + URLEncoder.encode(google, "UTF-8")

        val result = stripTracking(safeLinks)

        assertThat(result?.url).isEqualTo("https://site.example/page")
        assertThat(result?.skippedRedirects).isEqualTo(listOf("eur01.safelinks.protection.outlook.com", "google.com"))
    }

    @Test
    fun `a redirector whose destination is not a web link should be left alone`() {
        assertThat(stripTracking("https://l.facebook.com/l.php?u=javascript%3Aalert(1)")).isNull()
    }

    @Test
    fun `a redirector on another path should not be unwrapped`() {
        assertThat(stripTracking("https://www.google.com/search?q=https%3A%2F%2Fsite.example")).isNull()
    }

    @Test
    fun `a click tracker that hides its destination should be left alone`() {
        assertThat(stripTracking("https://u123.ct.sendgrid.net/ls/click?upn=u001.abcDEF")).isNull()
    }

    @Test
    fun `click tracking services should be recognised by their domain`() {
        assertThat(clickTrackerHost("https://gxjlzz.clicks.mlsend.com/gr/c/v2.k1.abc"))
            .isEqualTo("gxjlzz.clicks.mlsend.com")
        assertThat(clickTrackerHost("https://example.us1.list-manage.com/track/click?u=1&id=2"))
            .isEqualTo("example.us1.list-manage.com")
        assertThat(clickTrackerHost("https://t.co/AbC123")).isEqualTo("t.co")
    }

    @Test
    fun `a click tracking subdomain on the sender's own domain should be recognised`() {
        assertThat(clickTrackerHost("https://click.e.shop.example/?qs=abc")).isEqualTo("click.e.shop.example")
        assertThat(clickTrackerHost("https://links.news.example/ls/click?upn=x")).isEqualTo("links.news.example")
    }

    @Test
    fun `a Substack redirect should be recognised but a Substack post should not`() {
        assertThat(clickTrackerHost("https://writer.substack.com/redirect/2/eyJ1IjoxfQ"))
            .isEqualTo("writer.substack.com")
        assertThat(clickTrackerHost("https://writer.substack.com/p/a-post")).isNull()
    }

    @Test
    fun `a click tracker on the sender's own domain should be recognised by its path`() {
        assertThat(clickTrackerHost("https://ablink.email.rocketmoney.com/uni/ls/click?upn=u001.Wbl4MBulID6vB"))
            .isEqualTo("ablink.email.rocketmoney.com")
        assertThat(clickTrackerHost("https://url1234.shop.example/ls/click?upn=abc")).isEqualTo("url1234.shop.example")
        assertThat(clickTrackerHost("https://em.shop.example/wf/click?upn=abc")).isEqualTo("em.shop.example")
        assertThat(clickTrackerHost("https://news.shop.example/ss/c/u001.abc/4ab/xyz")).isEqualTo("news.shop.example")
    }

    @Test
    fun `a path that only resembles a tracking path should not count`() {
        assertThat(clickTrackerHost("https://shop.example/tools/clickers")).isNull()
        assertThat(clickTrackerHost("https://shop.example/blog/ss/c/post")).isNull()
    }

    @Test
    fun `a short link should be shown whole`() {
        val url = "https://shop.example/item?id=42"

        assertThat(shortenForDisplay(url)).isEqualTo(url)
    }

    @Test
    fun `a long link should be shown by its start and end`() {
        val url = "https://ablink.email.rocketmoney.com/uni/ls/click?upn=" + "x".repeat(1000) + "-END_OF_TOKEN"

        val shown = shortenForDisplay(url)

        assertThat(shown.length).isEqualTo(160)
        assertThat(shown.startsWith("https://ablink.email.rocketmoney.com/uni/ls/click?upn=")).isTrue()
        assertThat(shown.endsWith("-END_OF_TOKEN")).isTrue()
        assertThat(shown.contains("…")).isTrue()
    }

    @Test
    fun `ordinary sites should not be taken for click trackers`() {
        assertThat(clickTrackerHost("https://link.springer.com/article/1")).isNull()
        assertThat(clickTrackerHost("https://track.aftership.com/123")).isNull()
        assertThat(clickTrackerHost("https://github.com/bobcat0070")).isNull()
        assertThat(clickTrackerHost("https://notsendgrid.net/")).isNull()
        assertThat(clickTrackerHost("mailto:someone@example.com")).isNull()
    }

    @Test
    fun `a stray percent sign should not stop the rest of the link being read`() {
        assertThat(stripTracking("https://a.example/?q=100%&utm_term=x")?.url).isEqualTo("https://a.example/?q=100%")
    }
}
