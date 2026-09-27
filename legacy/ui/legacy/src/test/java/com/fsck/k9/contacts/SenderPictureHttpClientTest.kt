package com.fsck.k9.contacts

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SenderPictureHttpClientTest {
    private val server = MockWebServer()
    private val testSubject = senderPictureHttpClient(timeoutSeconds = 5)

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a URL naming a local address should not be fetched`() {
        // What a BIMI record pointing at the reader's router looks like. OkHttp connects to an IP address
        // without asking DNS, so filtering at resolution alone lets this through.
        server.enqueue(MockResponse().setBody("reached"))
        server.start()

        assertFailsWith<UnknownHostException> {
            testSubject.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/logo.svg").build()).execute()
        }

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a name resolving to a local address should not be fetched`() {
        server.enqueue(MockResponse().setBody("reached"))
        server.start()

        assertFailsWith<UnknownHostException> {
            testSubject.newCall(Request.Builder().url("http://localhost:${server.port}/logo.svg").build()).execute()
        }

        assertThat(server.requestCount).isEqualTo(0)
    }
}
