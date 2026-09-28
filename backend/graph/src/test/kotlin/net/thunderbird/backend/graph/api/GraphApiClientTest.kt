package net.thunderbird.backend.graph.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthenticationFailedException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

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
    fun `network failure should be reported as a temporary messaging failure`() {
        val testSubject = createTestSubject(FakeOAuth2TokenProvider())
        // OkHttp retries a dropped connection once on its own, so both attempts have to fail.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        // A pending command that fails with anything but a MessagingException is dropped instead of retried.
        val exception = assertFailsWith<MessagingException> {
            testSubject.getString(testSubject.url("me/mailFolders"))
        }

        assertThat(exception.isPermanentFailure).isEqualTo(false)
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

    @Test
    fun `a server ID containing a slash should stay one path segment`() {
        val testSubject = createTestSubject()

        val url = testSubject.url("me/messages/${pathSegment("AAMk/AD+x=")}/\$value")

        assertThat(url.encodedPath).isEqualTo("/v1.0/me/messages/AAMk%2FAD+x=/\$value")
    }

    @Test
    fun `a dot-segment ID should be refused`() {
        // URL resolution removes a dot segment however it is encoded, so the only safe answer is to refuse it.
        assertFailsWith<MessagingException> { pathSegment("..") }
    }

    @Test
    fun `a link to another host should be refused`() {
        // The access token goes to whatever URL is requested.
        val testSubject = createTestSubject()

        assertFailsWith<MessagingException> {
            testSubject.absoluteUrl("https://attacker.example/v1.0/me/messages?\$skiptoken=x")
        }
    }

    @Test
    fun `a link to the Graph host should be followed`() {
        val testSubject = createTestSubject()
        val link = "${server.url("/v1.0/")}me/messages?\$skiptoken=x"

        assertThat(testSubject.absoluteUrl(link).toString()).isEqualTo(link)
    }

    @Test
    fun `a response that keeps trickling in should be given up on as a temporary failure`() {
        // Each byte arrives well inside the read timeout, so only a limit on the whole request ends it.
        val testSubject = createTestSubject(callTimeoutMillis = 500)
        server.enqueue(MockResponse().setBody("""{"value":[]}""").throttleBody(1, 200, TimeUnit.MILLISECONDS))

        val exception = assertFailsWith<MessagingException> {
            testSubject.getString(testSubject.url("me/mailFolders/inbox/messages/delta"))
        }

        assertThat(exception.isPermanentFailure).isFalse()
    }

    @Test
    fun `a slow message download should not be cut off by the time limit`() {
        // A message with large attachments on a slow connection can rightly take a while.
        val testSubject = createTestSubject(callTimeoutMillis = 300)
        server.enqueue(MockResponse().setBody("From: a@example.com").throttleBody(4, 100, TimeUnit.MILLISECONDS))

        val content = testSubject.getStream(testSubject.url("me/messages/m1/\$value")) {
            it.readBytes().decodeToString()
        }

        assertThat(content).isEqualTo("From: a@example.com")
    }

    private fun createTestSubject(
        tokenProvider: FakeOAuth2TokenProvider = FakeOAuth2TokenProvider(),
        callTimeoutMillis: Long = 120_000,
    ): GraphApiClient {
        return GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = tokenProvider,
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { sleeps += it },
            callTimeoutMillis = callTimeoutMillis,
        )
    }
}
