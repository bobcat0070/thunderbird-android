package app.k9mail.feature.account.setup.domain

import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.folders.FolderFetcher
import com.fsck.k9.mail.folders.RemoteFolder
import com.fsck.k9.mail.oauth.AuthStateStorage
import net.thunderbird.core.common.mail.Protocols

/**
 * Routes a folder listing to the fetcher that speaks the account protocol.
 *
 * The individual fetchers reject server settings of another protocol outright, so an account has to be dispatched on
 * its type before the folder list can be read.
 */
class ProtocolFolderFetcher(
    private val imapFolderFetcher: FolderFetcher,
    private val graphFolderFetcher: FolderFetcher,
) : FolderFetcher {

    override fun getFolders(
        serverSettings: ServerSettings,
        authStateStorage: AuthStateStorage?,
    ): List<RemoteFolder> {
        return when (serverSettings.type) {
            Protocols.GRAPH -> graphFolderFetcher.getFolders(serverSettings, authStateStorage)
            else -> imapFolderFetcher.getFolders(serverSettings, authStateStorage)
        }
    }
}
