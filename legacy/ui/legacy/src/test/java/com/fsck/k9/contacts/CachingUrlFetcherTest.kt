package com.fsck.k9.contacts

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.thunderbird.core.android.testing.RobolectricTest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CachingUrlFetcherTest : RobolectricTest() {
    private lateinit var server: MockWebServer
    private val readCounter = ReadCountingInterceptor()
    private val testSubject = CachingUrlFetcher(
        httpClient = OkHttpClient.Builder().addInterceptor(readCounter).build(),
        cache = AvatarCache(ApplicationProvider.getApplicationContext()),
    )

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
    fun `should return what was fetched`() {
        server.enqueue(MockResponse.Builder().body("revocation list").build())

        val result = testSubject.fetch(server.url("/list.crl").toString())

        assertThat(result?.decodeToString()).isEqualTo("revocation list")
    }

    @Test
    fun `should stop reading a body of undeclared length at the limit`() {
        val tooLarge = Buffer().write(ByteArray(8 * 1024 * 1024))
        server.enqueue(MockResponse.Builder().chunkedBody(tooLarge, 64 * 1024).build())

        val result = testSubject.fetch(server.url("/list.crl").toString())

        assertThat(result).isNull()
        assertThat(readCounter.bytesRead).isLessThan(3L * 1024 * 1024)
    }
}
