package net.thunderbird.backend.graph

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.AuthStateStorage
import com.fsck.k9.mail.oauth.OAuth2TokenProviderFactory
import com.fsck.k9.mail.server.ServerSettingsValidationResult
import com.fsck.k9.mail.server.ServerSettingsValidator
import java.io.IOException
import net.thunderbird.backend.graph.api.GRAPH_BASE_URL
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.OkHttpClient

/**
 * Verifies that an account can actually be used through Microsoft Graph.
 *
 * The check requests the inbox, which exercises the whole path in one call: the stored authorization state produces a
 * usable access token, the token is accepted, and the tenant has granted the app the mail permissions it needs. A
 * tenant that withholds admin consent for those permissions is a common failure here, and it surfaces as an
 * authentication error rather than as a silent sync failure later.
 */
class GraphServerSettingsValidator(
    private val oAuth2TokenProviderFactory: OAuth2TokenProviderFactory,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = GRAPH_BASE_URL,
) : ServerSettingsValidator {

    @Suppress("TooGenericExceptionCaught")
    override fun checkServerSettings(
        serverSettings: ServerSettings,
        authStateStorage: AuthStateStorage?,
    ): ServerSettingsValidationResult {
        if (authStateStorage == null) {
            return ServerSettingsValidationResult.AuthenticationError(serverMessage = null)
        }

        val client = GraphApiClient(
            okHttpClient = okHttpClient,
            tokenProvider = oAuth2TokenProviderFactory.create(authStateStorage),
            baseUrl = baseUrl,
        )

        return try {
            client.getString(
                client.url("me/mailFolders/inbox") {
                    addQueryParameter("\$select", "id")
                },
            )

            ServerSettingsValidationResult.Success
        } catch (e: AuthenticationFailedException) {
            ServerSettingsValidationResult.AuthenticationError(e.messageFromServer)
        } catch (e: IOException) {
            ServerSettingsValidationResult.NetworkError(e)
        } catch (e: MessagingException) {
            ServerSettingsValidationResult.ServerError(e.message)
        } catch (e: Exception) {
            ServerSettingsValidationResult.UnknownError(e)
        }
    }
}
