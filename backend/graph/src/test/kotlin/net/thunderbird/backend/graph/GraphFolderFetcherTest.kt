package net.thunderbird.backend.graph

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.folders.FolderFetcherException
import com.fsck.k9.mail.oauth.AuthStateStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class GraphFolderFetcherTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `should list folders with their well known types`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[
                  {"id":"inbox-id","displayName":"Inbox","childFolderCount":0},
                  {"id":"custom-id","displayName":"Receipts","childFolderCount":0}
                ]}
                """.trimIndent(),
            ),
        )
        // Graph answers every lookup in the batch; the folders other than the Inbox do not exist in this mailbox.
        val notFound = (1..6).joinToString(",") { """{"id":"$it","status":404,"body":{}}""" }
        server.enqueue(
            MockResponse().setBody("""{"responses":[{"id":"0","status":200,"body":{"id":"inbox-id"}},$notFound]}"""),
        )

        val result = createTestSubject().getFolders(GRAPH_SERVER_SETTINGS, FakeAuthStateStorage())

        assertThat(result.map { it.serverId.serverId }).containsExactlyInAnyOrder("inbox-id", "custom-id")
        assertThat(result.first { it.serverId.serverId == "inbox-id" }.type).isEqualTo(FolderType.INBOX)
        assertThat(result.first { it.serverId.serverId == "custom-id" }.type).isEqualTo(FolderType.REGULAR)
    }

    @Test
    fun `should reject settings for another protocol`() {
        val imapSettings = GRAPH_SERVER_SETTINGS.copy(type = "imap")

        assertFailure {
            createTestSubject().getFolders(imapSettings, FakeAuthStateStorage())
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `should fail cleanly without an authorization state`() {
        // Graph has no password fallback, so there is nothing to attempt without OAuth.
        assertFailure {
            createTestSubject().getFolders(GRAPH_SERVER_SETTINGS, authStateStorage = null)
        }.isInstanceOf<FolderFetcherException>()
    }

    @Test
    fun `server error should be reported as a folder fetcher failure`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"ErrorAccessDenied"}}"""))

        val exception = assertFailure {
            createTestSubject().getFolders(GRAPH_SERVER_SETTINGS, FakeAuthStateStorage())
        }.isInstanceOf<FolderFetcherException>()

        exception.transform { it.messageFromServer }.isEqualTo("ErrorAccessDenied")
    }

    private fun createTestSubject() = GraphFolderFetcher(
        oAuth2TokenProviderFactory = { FakeOAuth2TokenProvider() },
        okHttpClient = OkHttpClient(),
        baseUrl = server.url("/v1.0/").toString(),
    )

    private class FakeAuthStateStorage : AuthStateStorage {
        override fun getAuthorizationState(): String = "state"
        override fun updateAuthorizationState(authorizationState: String?) = Unit
    }

    private companion object {
        val GRAPH_SERVER_SETTINGS = ServerSettings(
            type = "graph",
            host = "graph.microsoft.com",
            port = 443,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.XOAUTH2,
            username = "user@company.example",
            password = null,
            clientCertificateAlias = null,
        )
    }
}
