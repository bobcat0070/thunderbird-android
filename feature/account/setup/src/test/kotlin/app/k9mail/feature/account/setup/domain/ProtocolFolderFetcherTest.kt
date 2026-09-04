package app.k9mail.feature.account.setup.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.folders.FolderFetcher
import com.fsck.k9.mail.folders.FolderServerId
import com.fsck.k9.mail.folders.RemoteFolder
import com.fsck.k9.mail.oauth.AuthStateStorage
import kotlin.test.Test

class ProtocolFolderFetcherTest {

    @Test
    fun `graph account should be routed to the graph folder fetcher`() {
        val imapFolderFetcher = RecordingFolderFetcher(folder("imap-folder"))
        val graphFolderFetcher = RecordingFolderFetcher(folder("graph-folder"))
        val testSubject = ProtocolFolderFetcher(imapFolderFetcher, graphFolderFetcher)

        val result = testSubject.getFolders(GRAPH_SERVER_SETTINGS, authStateStorage = null)

        assertThat(result.map { it.displayName }).containsExactly("graph-folder")
        assertThat(graphFolderFetcher.receivedSettings).containsExactly(GRAPH_SERVER_SETTINGS)
        assertThat(imapFolderFetcher.receivedSettings).isEmpty()
    }

    @Test
    fun `imap account should be routed to the imap folder fetcher`() {
        val imapFolderFetcher = RecordingFolderFetcher(folder("imap-folder"))
        val graphFolderFetcher = RecordingFolderFetcher(folder("graph-folder"))
        val testSubject = ProtocolFolderFetcher(imapFolderFetcher, graphFolderFetcher)

        val result = testSubject.getFolders(IMAP_SERVER_SETTINGS, authStateStorage = null)

        assertThat(result.map { it.displayName }).containsExactly("imap-folder")
        assertThat(imapFolderFetcher.receivedSettings).containsExactly(IMAP_SERVER_SETTINGS)
        assertThat(graphFolderFetcher.receivedSettings).isEmpty()
    }

    private class RecordingFolderFetcher(
        private vararg val folders: RemoteFolder,
    ) : FolderFetcher {
        val receivedSettings = mutableListOf<ServerSettings>()

        override fun getFolders(
            serverSettings: ServerSettings,
            authStateStorage: AuthStateStorage?,
        ): List<RemoteFolder> {
            receivedSettings += serverSettings

            return folders.toList()
        }
    }

    private companion object {
        fun folder(name: String) = RemoteFolder(
            serverId = FolderServerId(name),
            displayName = name,
            type = FolderType.REGULAR,
        )

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

        val IMAP_SERVER_SETTINGS = ServerSettings(
            type = "imap",
            host = "imap.company.example",
            port = 993,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = "user",
            password = "password",
            clientCertificateAlias = null,
        )
    }
}
