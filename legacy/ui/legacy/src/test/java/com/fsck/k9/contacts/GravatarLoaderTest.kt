package com.fsck.k9.contacts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.core.preference.GeneralSettings
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.core.preference.gravatar.GravatarSettings
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GravatarLoaderTest : RobolectricTest() {
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
        val testSubject = loaderFor(GravatarSettings(isEnabled = false))

        val result = testSubject.loadGravatar("sam@example.com", size = 80)

        assertThat(result).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `should hash the address rather than send it`() {
        // The whole reason the lookup is acceptable: gravatar.com is told a hash, not the address.
        server.enqueue(MockResponse(code = 404))
        val testSubject = loaderFor(GravatarSettings(isEnabled = true))

        testSubject.loadGravatar("Sam@Example.COM", size = 80)

        val path = server.takeRequest().target
        // SHA-256 of the lower-cased address.
        assertThat(path.substringAfterLast('/').substringBefore('?'))
            .isEqualTo("cd25a6171969f2a3c6e35c7667e3908ef1bd2424241db04411a0eec454ca6c16")
    }

    @Test
    fun `should send the api key as a bearer token when one is configured`() {
        server.enqueue(MockResponse(code = 404))
        val testSubject = loaderFor(GravatarSettings(isEnabled = true, apiKey = "key-123"))

        testSubject.loadGravatar("sam@example.com", size = 80)

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer key-123")
    }

    @Test
    fun `should send no authorization header when no api key is configured`() {
        // The endpoint serves anonymous requests, so an empty key must not become "Bearer ".
        server.enqueue(MockResponse(code = 404))
        val testSubject = loaderFor(GravatarSettings(isEnabled = true, apiKey = ""))

        testSubject.loadGravatar("sam@example.com", size = 80)

        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `should ask for a miss only once`() {
        // Misses are the common case, and without this every scroll past the row would ask again.
        server.enqueue(MockResponse(code = 404))
        val testSubject = loaderFor(GravatarSettings(isEnabled = true))

        testSubject.loadGravatar("sam@example.com", size = 80)
        testSubject.loadGravatar("sam@example.com", size = 80)

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `should retry after a server error`() {
        // A rate limit or an outage says nothing about whether this address has a Gravatar.
        server.enqueue(MockResponse(code = 429))
        server.enqueue(MockResponse(code = 429))
        val testSubject = loaderFor(GravatarSettings(isEnabled = true))

        testSubject.loadGravatar("sam@example.com", size = 80)
        testSubject.loadGravatar("sam@example.com", size = 80)

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `should decode a returned image`() {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(onePixelPng())).build())
        val testSubject = loaderFor(GravatarSettings(isEnabled = true))

        val result = testSubject.loadGravatar("sam@example.com", size = 80)

        assertThat(result).isNotNull()
    }

    @Test
    fun `should return nothing for a blank address`() {
        val testSubject = loaderFor(GravatarSettings(isEnabled = true))

        val result = testSubject.loadGravatar("   ", size = 80)

        assertThat(result).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun loaderFor(settings: GravatarSettings): GravatarLoader {
        val generalSettingsManager = mock<GeneralSettingsManager>()
        val generalSettings = mock<GeneralSettings>()
        whenever(generalSettings.gravatar).thenReturn(settings)
        whenever(generalSettingsManager.getConfig()).thenReturn(generalSettings)

        return GravatarLoader(
            generalSettingsManager = generalSettingsManager,
            httpClient = OkHttpClient(),
            logger = TestLogger(),
            baseUrl = server.url("/avatar/").toString(),
        )
    }

    private fun onePixelPng(): ByteArray {
        val base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        return java.util.Base64.getDecoder().decode(base64)
    }
}
