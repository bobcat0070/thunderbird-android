package net.thunderbird.backend.graph

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.folders.FolderFetcher
import com.fsck.k9.mail.folders.FolderFetcherException
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.folders.RemoteFolder
import com.fsck.k9.mail.oauth.AuthStateStorage
import com.fsck.k9.mail.oauth.OAuth2TokenProviderFactory
import net.thunderbird.backend.graph.api.GRAPH_BASE_URL
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphFolderLister
import net.thunderbird.backend.graph.api.pathName
import net.thunderbird.backend.graph.api.resolveWellKnownFolderTypes
import net.thunderbird.core.common.mail.Protocols
import okhttp3.OkHttpClient

/**
 * Lists the folders of a Microsoft Graph mailbox, so account setup can offer them as special folders.
 *
 * This exists because folder listing during setup happens before an account has a backend.
 */
class GraphFolderFetcher(
    private val oAuth2TokenProviderFactory: OAuth2TokenProviderFactory,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = GRAPH_BASE_URL,
) : FolderFetcher {

    @Suppress("TooGenericExceptionCaught")
    override fun getFolders(
        serverSettings: ServerSettings,
        authStateStorage: AuthStateStorage?,
    ): List<RemoteFolder> {
        require(serverSettings.type == Protocols.GRAPH) { "Expected a Microsoft Graph account" }

        val storage = authStateStorage
            ?: throw FolderFetcherException(AuthenticationFailedException("Microsoft Graph requires OAuth"))

        val client = GraphApiClient(
            okHttpClient = okHttpClient,
            tokenProvider = oAuth2TokenProviderFactory.create(storage),
            baseUrl = baseUrl,
        )

        return try {
            val folders = GraphFolderLister(client).listFolders()
            val wellKnownTypes = client.resolveWellKnownFolderTypes()
            val foldersById = folders.associateBy { it.id }

            folders.map { folder ->
                RemoteFolder(
                    serverId = FolderServerId(folder.id),
                    displayName = folder.pathName(foldersById),
                    type = wellKnownTypes[folder.id] ?: FolderType.REGULAR,
                )
            }
        } catch (e: Exception) {
            throw FolderFetcherException(e, (e as? AuthenticationFailedException)?.messageFromServer)
        }
    }
}
