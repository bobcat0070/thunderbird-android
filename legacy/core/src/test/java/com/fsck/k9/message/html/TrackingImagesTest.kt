package com.fsck.k9.message.html

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.blocklist.BlocklistMatch

class TrackingImagesTest {
    @Test
    fun `a one pixel image should be blocked and named by its host`() {
        val html = """<p>Hi</p><img src="https://email.shop.example/o/abc" width="1" height="1" alt="">"""

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.html).isEqualTo(
            """<p>Hi</p><img data-blocked-src="https://email.shop.example/o/abc" width="1" height="1" alt="">""",
        )
        assertThat(testSubject.trackers)
            .containsExactly(TrackingImage(url = "https://email.shop.example/o/abc", service = "email.shop.example"))
    }

    @Test
    fun `an image hidden or sized to nothing by its style should be blocked`() {
        val html = """
            <img src="https://a.example/p.gif" style="display:none">
            <img src="https://b.example/p.gif" style="width:1px;height:1px">
            <img src="https://c.example/p.gif" style="max-height: 0">
            <img src="https://d.example/p.gif" style="opacity: 0 !important">
        """.trimIndent()

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.trackers.map { it.service })
            .containsExactly("a.example", "b.example", "c.example", "d.example")
    }

    @Test
    fun `an image from a known tracking service should be blocked and named for it`() {
        val html = """<img src="https://shop.us1.list-manage.com/track/open.php?u=1&amp;id=2" width="600">"""

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.trackers).containsExactly(
            TrackingImage(url = "https://shop.us1.list-manage.com/track/open.php?u=1&id=2", service = "Mailchimp"),
        )
    }

    @Test
    fun `an image on an open tracking path should be blocked whatever its host`() {
        val html = """<img src="https://email.brand.example/wf/open?upn=xyz">"""

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.trackers.map { it.service }).containsExactly("email.brand.example")
    }

    @Test
    fun `an image from a blocklisted domain should be blocked`() {
        val html = """<img src="https://ads.tracker.example/banner.png" width="600" height="200">"""

        val testSubject = blockTrackingImages(html) { host ->
            BlocklistMatch(host, BlocklistKind.GENERAL, "Normal").takeIf { host == "ads.tracker.example" }
        }

        assertThat(testSubject.trackers.map { it.service }).containsExactly("ads.tracker.example")
    }

    @Test
    fun `a srcset should be blocked along with the src`() {
        val html = """<img src="https://a.example/p.gif" srcset="https://a.example/p2.gif 2x" width="1" height="1">"""

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.html).isEqualTo(
            """<img data-blocked-src="https://a.example/p.gif" data-blocked-srcset="https://a.example/p2.gif 2x" """ +
                """width="1" height="1">""",
        )
    }

    @Test
    fun `ordinary images should be left as they are`() {
        val html = """
            <img src="https://cdn.shop.example/hero.jpg" width="600" height="300" alt="Sale">
            <img src="https://cdn.shop.example/logo.png">
            <img src="https://cdn.shop.example/line.png" width="600" height="1">
            <img src="cid:part1" width="1" height="1">
        """.trimIndent()

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.trackers).isEmpty()
        assertThat(testSubject.html).isEqualTo(html)
    }

    @Test
    fun `services should be listed once each, most often found first`() {
        val html = """
            <img src="https://a.example/1" width="1" height="1">
            <img src="https://x.sendgrid.net/wf/open?a=1">
            <img src="https://y.sendgrid.net/wf/open?a=2">
        """.trimIndent()

        val testSubject = blockTrackingImages(html)

        assertThat(testSubject.services).containsExactly("SendGrid", "a.example")
    }
}
