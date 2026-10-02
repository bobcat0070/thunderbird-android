package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.FolderType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private const val FOLDER_ID = "inbox-id"

/**
 * Where in a sync the ids of stored messages are converted to immutable ones. The conversion itself is covered by
 * [GraphImmutableIdMigrationTest].
 */
class GraphSyncIdConversionTest {
    private val server = MockWebServer()
    private val backendStorage = InMemoryBackendStorage()
    private val failures = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stored ids should be converted before anything is synchronized`() = runTest {
        createFolder()
        // The window probe, then a delta round that reports nothing.
        server.enqueue(MockResponse().setBody("""{"value": []}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"value": [], "@odata.deltaLink": "${server.url("/v1.0/")}me/delta?token=t1"}""",
            ),
        )
        val requestsBeforeConversion = mutableListOf<Int>()

        createTestSubject { folderServerId, _, _ ->
            assertThat(folderServerId).isEqualTo(FOLDER_ID)
            requestsBeforeConversion += server.requestCount
        }
            .sync(FOLDER_ID, syncConfig(syncRemoteDeletions = true), listener)

        assertThat(requestsBeforeConversion).containsExactly(0)
        assertThat(failures).isEmpty()
    }

    @Test
    fun `whether deletions are followed should decide whether the conversion removes missing messages`() = runTest {
        createFolder()
        val removeMissingValues = mutableListOf<Boolean>()
        val testSubject = createTestSubject { _, _, removeMissing ->
            removeMissingValues += removeMissing
            throw MessagingException("stop here", false)
        }

        testSubject.sync(FOLDER_ID, syncConfig(syncRemoteDeletions = true), listener)
        testSubject.sync(FOLDER_ID, syncConfig(syncRemoteDeletions = false), listener)

        assertThat(removeMissingValues).containsExactly(true, false)
    }

    @Test
    fun `a failed conversion should fail the sync without synchronizing anything`() = runTest {
        // The round reports messages by their immutable ids. Run against ids that were not converted, it would
        // store every message a second time.
        createFolder()

        createTestSubject { _, _, _ -> throw MessagingException("not converted", false) }
            .sync(FOLDER_ID, syncConfig(syncRemoteDeletions = true), listener)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(failures).hasSize(1)
    }

    private fun createFolder() {
        backendStorage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX)))
        }
    }

    private fun createTestSubject(migrateToImmutableIds: (String, BackendFolder, Boolean) -> Unit): GraphSync {
        return GraphSync(
            backendStorage = backendStorage,
            client = GraphApiClient(
                okHttpClient = OkHttpClient(),
                tokenProvider = FakeOAuth2TokenProvider(),
                baseUrl = server.url("/v1.0/").toString(),
                sleeper = { },
            ),
            logger = TestLogger(),
            readLastActions = { _, messages -> messages },
            backfillLastActions = { _, _, _, _ -> },
            migrateToImmutableIds = migrateToImmutableIds,
        )
    }

    private fun syncConfig(syncRemoteDeletions: Boolean) = SyncConfig(
        expungePolicy = SyncConfig.ExpungePolicy.IMMEDIATELY,
        earliestPollDate = null,
        syncRemoteDeletions = syncRemoteDeletions,
        maximumAutoDownloadMessageSize = 0,
        defaultVisibleLimit = 25,
        syncFlags = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED),
    )

    private val listener = object : SyncListener {
        override fun syncStarted(folderServerId: String) = Unit
        override fun syncAuthenticationSuccess() = Unit
        override fun syncHeadersStarted(folderServerId: String) = Unit
        override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
        override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) =
            Unit

        override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit
        override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) = Unit
        override fun syncRemovedMessage(folderServerId: String, messageServerId: String) = Unit
        override fun syncFlagChanged(folderServerId: String, messageServerId: String) = Unit
        override fun syncFinished(folderServerId: String) = Unit
        override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
            failures += message
        }

        override fun folderStatusChanged(folderServerId: String) = Unit
    }
}
