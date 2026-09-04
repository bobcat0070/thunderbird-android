package com.fsck.k9.contacts

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.util.Base64
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.core.preference.GeneralSettings
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.core.preference.websiteicon.WebsiteIconSettings
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WebsiteIconLoaderTest : RobolectricTest() {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `should not make a request when disabled`() {
        // Off by default, and the point of the setting is that no third party hears about the user's mail
        // until they say so.
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = false))

        val result = testSubject.loadIcon("example.com")

        assertThat(result).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `should ask the service for the sender domain`() {
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("example.com")

        val target = server.takeRequest().target
        assertThat(target).contains("example.com")
        // Without this the service answers with its placeholder for every domain, known or not.
        assertThat(target).contains("fallback_opts")
    }

    @Test
    fun `should decode a returned icon`() {
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.loadIcon("example.com")).isNotNull()
    }

    @Test
    fun `should treat the service placeholder as no icon`() {
        // The service answers 200 with a generic globe for a domain it has no icon for. Nothing else in the
        // response distinguishes it, so showing it would put a globe beside a third of the senders tried.
        server.enqueue(placeholderResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.loadIcon("unknown.example")).isNull()
    }

    @Test
    fun `should remember the placeholder as a miss`() {
        // "This domain has no icon" is a real answer and the common one; re-asking on every scroll past the
        // row would be both slow and a repeated disclosure.
        server.enqueue(placeholderResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("unknown.example")
        testSubject.loadIcon("unknown.example")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `should reuse an icon it already has`() {
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("example.com")
        testSubject.loadIcon("example.com")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `should retry after a server error`() {
        // A rate limit or an outage says nothing about whether this domain has an icon.
        server.enqueue(MockResponse(code = 429))
        server.enqueue(MockResponse(code = 429))
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("example.com")
        testSubject.loadIcon("example.com")

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `should look up the parent when a sending subdomain has no icon of its own`() {
        // Bulk mail is almost never sent from the domain whose website people know: sending subdomains of
        // the shape e.<brand>.com or mg.<brand>.com have no icon while their parents do.
        server.enqueue(placeholderResponse())
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.loadIcon("email.example.com")).isNotNull()

        assertThat(server.takeRequest().target).contains("email.example.com")
        assertThat(server.takeRequest().target).contains("https://example.com")
    }

    @Test
    fun `should prefer an icon the sending subdomain has of its own`() {
        // A sending subdomain sometimes publishes its own icon, and the more specific one is the better
        // answer.
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("snacks.example.com")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `should stop walking two labels from the end`() {
        // Overshooting into a public suffix is harmless - the service has no icon for co.uk - but there is
        // nothing above it worth asking for either.
        server.enqueue(placeholderResponse())
        server.enqueue(placeholderResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.loadIcon("mail.example.com")).isNull()

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `should walk no further than the cap`() {
        // Every attempt is another request naming another domain, so one unknown sender must not become an
        // unbounded run of them.
        repeat(times = 5) { server.enqueue(placeholderResponse()) }
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("a.b.c.d.example.com")

        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a parent found for one subdomain should serve another`() {
        server.enqueue(placeholderResponse())
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))
        testSubject.loadIcon("news.example.com")

        server.enqueue(placeholderResponse())
        assertThat(testSubject.loadIcon("alerts.example.com")).isNotNull()

        // The second sender asks only about itself; the parent is already known.
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a not found response should count as no icon and keep walking`() {
        // The service has two ways of saying it has no icon: its placeholder image, and a plain 404. Which
        // one arrives depends on where the request comes from, and treating the 404 as a failure stopped the
        // walk at the sending subdomain - where bulk senders never have an icon.
        server.enqueue(MockResponse(code = 404))
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.loadIcon("email.example.com")).isNotNull()

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a not found response should be remembered`() {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 404))
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))
        testSubject.loadIcon("example.com")

        testSubject.loadIcon("example.com")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an unreachable service should not be remembered as having no icon`() {
        // Otherwise one outage while a mailbox first scrolls would silently cost every sender their icon for
        // as long as the miss is cached.
        server.enqueue(MockResponse(code = 503))
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))
        testSubject.loadIcon("example.com")

        server.enqueue(imageResponse())
        assertThat(testSubject.loadIcon("example.com")).isNotNull()
    }

    @Test
    fun `should ignore case and surrounding space in the domain`() {
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        testSubject.loadIcon("  Example.COM  ")
        testSubject.loadIcon("example.com")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `should return nothing for a blank domain`() {
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.loadIcon("   ")).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `asking whether an icon is held should never cause a lookup`() {
        // The message view uses this to caption the picture. A caption that went to the network could arrive
        // disagreeing with the picture already drawn.
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))

        assertThat(testSubject.hasCachedIconFor("example.com")).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an icon already held should be reported`() {
        server.enqueue(imageResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))
        testSubject.loadIcon("example.com")

        assertThat(testSubject.hasCachedIconFor("example.com")).isTrue()
    }

    @Test
    fun `a remembered miss should not be reported as an icon`() {
        server.enqueue(placeholderResponse())
        val testSubject = loaderFor(WebsiteIconSettings(isEnabled = true))
        testSubject.loadIcon("unknown.example")

        assertThat(testSubject.hasCachedIconFor("unknown.example")).isFalse()
    }

    @Test
    fun `nothing should be reported while the setting is off`() {
        server.enqueue(imageResponse())
        val enabled = loaderFor(WebsiteIconSettings(isEnabled = true))
        enabled.loadIcon("example.com")

        val disabled = loaderFor(WebsiteIconSettings(isEnabled = false))

        assertThat(disabled.hasCachedIconFor("example.com")).isFalse()
    }

    private fun loaderFor(settings: WebsiteIconSettings): WebsiteIconLoader {
        val generalSettingsManager = mock<GeneralSettingsManager>()
        val generalSettings = mock<GeneralSettings>()
        whenever(generalSettings.websiteIcon).thenReturn(settings)
        whenever(generalSettingsManager.getConfig()).thenReturn(generalSettings)

        return WebsiteIconLoader(
            generalSettingsManager = generalSettingsManager,
            httpClient = OkHttpClient(),
            cache = AvatarCache(ApplicationProvider.getApplicationContext()),
            logger = TestLogger(),
            baseUrl = server.url("/faviconV2").toString(),
        )
    }

    private fun imageResponse() = MockResponse.Builder().code(200).body(Buffer().write(onePixelPng())).build()

    /**
     * The real placeholder, so the digest the loader pins is the one being matched rather than a stand-in.
     */
    private fun placeholderResponse() =
        MockResponse.Builder().code(200).body(Buffer().write(placeholderPng())).build()

    private fun onePixelPng(): ByteArray {
        val base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        return Base64.getDecoder().decode(base64)
    }

    private fun placeholderPng(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("website-icon-placeholder.png")!!.use { it.readBytes() }
}
