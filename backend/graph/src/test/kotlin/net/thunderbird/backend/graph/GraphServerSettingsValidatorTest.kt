package net.thunderbird.backend.graph

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.AuthStateStorage
import com.fsck.k9.mail.server.ServerSettingsValidationResult
import kotlin.test.AfterTest
import kotlin.test.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class GraphServerSettingsValidatorTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reaching the inbox should validate the account`() {
        server.enqueue(MockResponse().setBody("""{"id":"inbox-id"}"""))

        val result = createTestSubject().checkServerSettings(GRAPH_SERVER_SETTINGS, FakeAuthStateStorage())

        assertThat(result).isEqualTo(ServerSettingsValidationResult.Success)
        assertThat(server.takeRequest().path).isEqualTo("/v1.0/me/mailFolders/inbox?%24select=id")
    }

    @Test
    fun `missing authorization state should be an authentication error`() {
        val result = createTestSubject().checkServerSettings(GRAPH_SERVER_SETTINGS, authStateStorage = null)

        assertThat(result).isInstanceOf<ServerSettingsValidationResult.AuthenticationError>()
    }

    @Test
    fun `a tenant withholding mail permissions should surface as an authentication error`() {
        // A tenant that has not consented to the app returns 403 rather than 401.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"ErrorAccessDenied"}}"""))

        val result = createTestSubject().checkServerSettings(GRAPH_SERVER_SETTINGS, FakeAuthStateStorage())

        assertThat(result).isInstanceOf<ServerSettingsValidationResult.AuthenticationError>()
            .transform { it.serverMessage }
            .isEqualTo("ErrorAccessDenied")
    }

    @Test
    fun `a server fault should be reported as a server error rather than bad credentials`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"InternalServerError"}}"""))

        val result = createTestSubject().checkServerSettings(GRAPH_SERVER_SETTINGS, FakeAuthStateStorage())

        assertThat(result).isInstanceOf<ServerSettingsValidationResult.ServerError>()
    }

    @Test
    fun `a dropped connection should be reported as a network error`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = createTestSubject().checkServerSettings(GRAPH_SERVER_SETTINGS, FakeAuthStateStorage())

        assertThat(result).isInstanceOf<ServerSettingsValidationResult.NetworkError>()
    }

    private fun createTestSubject() = GraphServerSettingsValidator(
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
