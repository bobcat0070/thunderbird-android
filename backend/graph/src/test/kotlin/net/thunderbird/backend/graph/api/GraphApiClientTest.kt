package net.thunderbird.backend.graph.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthenticationFailedException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class GraphApiClientTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `request should carry the access token as a bearer token`() {
        val testSubject = createTestSubject()
        server.enqueue(MockResponse().setBody("""{"id":"folder-id"}"""))

        testSubject.getString(testSubject.url("me/mailFolders/inbox"))

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token")
    }

    @Test
    fun `rejected token should be invalidated and the request retried once`() {
        val tokenProvider = FakeOAuth2TokenProvider(tokens = listOf("stale-token", "fresh-token"))
        val testSubject = createTestSubject(tokenProvider)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"id":"folder-id"}"""))

        val result = testSubject.getString(testSubject.url("me/mailFolders/inbox"))

        assertThat(result).isEqualTo("""{"id":"folder-id"}""")
        assertThat(tokenProvider.invalidateCount).isEqualTo(1)
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer stale-token")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fresh-token")
    }

    @Test
    fun `repeated authentication failure should be reported and not retried forever`() {
        val tokenProvider = FakeOAuth2TokenProvider()
        val testSubject = createTestSubject(tokenProvider)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"InvalidAuthenticationToken"}}"""),
        )

        val exception = assertFailsWith<AuthenticationFailedException> {
            testSubject.getString(testSubject.url("me/mailFolders/inbox"))
        }

        assertThat(exception.messageFromServer).isEqualTo("InvalidAuthenticationToken")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `throttled request should be retried after the delay the server asked for`() {
        val testSubject = createTestSubject()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setBody("""{"id":"folder-id"}"""))

        val result = testSubject.getString(testSubject.url("me/mailFolders/inbox"))

        assertThat(result).isEqualTo("""{"id":"folder-id"}""")
        assertThat(sleeps).isEqualTo(listOf(2000L))
    }

    @Test
    fun `server error should be reported as a temporary failure`() {
        val testSubject = createTestSubject()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"InternalServerError"}}"""))

        val exception = assertFailsWith<MessagingException> {
            testSubject.getString(testSubject.url("me/mailFolders/inbox"))
        }

        // A permanent failure would stop the account from syncing until the user intervenes.
        assertThat(exception.isPermanentFailure).isEqualTo(false)
    }

    @Test
    fun `missing mail permissions should be reported as an authentication problem`() {
        val testSubject = createTestSubject()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"ErrorAccessDenied"}}"""))

        val exception = assertFailsWith<AuthenticationFailedException> {
            testSubject.getString(testSubject.url("me/mailFolders/inbox"))
        }

        assertThat(exception.messageFromServer).isEqualTo("ErrorAccessDenied")
    }

    @Test
    fun `error response without a JSON body should still produce a messaging exception`() {
        val testSubject = createTestSubject()
        server.enqueue(MockResponse().setResponseCode(400).setBody("not json"))

        val exception = assertFailsWith<MessagingException> {
            testSubject.getString(testSubject.url("me/mailFolders/inbox"))
        }

        assertThat(exception).isInstanceOf<MessagingException>()
        assertThat(exception.isPermanentFailure).isTrue()
    }

    @Test
    fun `paths and query parameters should be appended to the base URL`() {
        val testSubject = createTestSubject()
        server.enqueue(MockResponse().setBody("{}"))

        testSubject.getString(
            testSubject.url("me/mailFolders/inbox/messages") {
                addQueryParameter("\$select", "id")
            },
        )

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1.0/me/mailFolders/inbox/messages?%24select=id")
    }

    private fun createTestSubject(
        tokenProvider: FakeOAuth2TokenProvider = FakeOAuth2TokenProvider(),
    ): GraphApiClient {
        return GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = tokenProvider,
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { sleeps += it },
        )
    }
}
