package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessageDownloadState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.LAST_VERB_FORWARD
import net.thunderbird.backend.graph.api.LAST_VERB_REPLY_TO_SENDER
import net.thunderbird.backend.graph.api.toEnvelopeMessage
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

private const val FOLDER_ID = "inbox-id"

class GraphLastActionBackfillTest {
    private val graph = FakeLastActionGraph()
    private val server = MockWebServer().apply { dispatcher = graph }
    private val backendStorage = InMemoryBackendStorage()
    private val listener = FlagChangeListener()
    private val testSubject = GraphLastActionBackfill(
        GraphLastActionReader(
            client = GraphApiClient(
                okHttpClient = OkHttpClient(),
                tokenProvider = FakeOAuth2TokenProvider(),
                baseUrl = server.url("/v1.0/").toString(),
                sleeper = { },
            ),
            logger = TestLogger(),
        ),
    )

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stored messages replied to or forwarded in Outlook should get their arrows`() = runTest {
        // Stored before replied and forwarded were read at all; delta will never report them again.
        val folder = createFolderWith("m1", "m2", "m3")
        graph.verbs["m1"] = LAST_VERB_REPLY_TO_SENDER
        graph.verbs["m2"] = LAST_VERB_FORWARD

        testSubject.backfillIfDue(FOLDER_ID, folder, syncConfig(), listener)

        assertThat(folder.getMessageFlags("m1")).containsExactlyInAnyOrder(Flag.ANSWERED)
        assertThat(folder.getMessageFlags("m2")).containsExactlyInAnyOrder(Flag.FORWARDED)
        assertThat(folder.getMessageFlags("m3")).containsExactlyInAnyOrder()
        assertThat(listener.changed).containsExactlyInAnyOrder("m1", "m2")
    }

    @Test
    fun `other flags should be left as they are`() = runTest {
        // The listing carries only the last action; reading it as "not read, not starred" would undo both.
        val folder = createFolderWith("m1")
        folder.setMessageFlag("m1", Flag.SEEN, true)
        folder.setMessageFlag("m1", Flag.FLAGGED, true)
        graph.verbs["m1"] = LAST_VERB_REPLY_TO_SENDER

        testSubject.backfillIfDue(FOLDER_ID, folder, syncConfig(), listener)

        assertThat(folder.getMessageFlags("m1")).containsExactlyInAnyOrder(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED)
    }

    @Test
    fun `a folder should be read once`() = runTest {
        val folder = createFolderWith("m1")

        testSubject.backfillIfDue(FOLDER_ID, folder, syncConfig(), listener)
        testSubject.backfillIfDue(FOLDER_ID, folder, syncConfig(), listener)

        assertThat(graph.requestCount).isEqualTo(1)
    }

    @Test
    fun `a refused listing should be tried again next time`() = runTest {
        val folder = createFolderWith("m1")
        graph.isRefusing = true

        testSubject.backfillIfDue(FOLDER_ID, folder, syncConfig(), listener)

        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_LAST_ACTIONS_BACKFILLED)).isNull()

        graph.isRefusing = false
        graph.verbs["m1"] = LAST_VERB_FORWARD
        testSubject.backfillIfDue(FOLDER_ID, folder, syncConfig(), listener)

        assertThat(folder.getMessageFlags("m1")).containsExactlyInAnyOrder(Flag.FORWARDED)
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_LAST_ACTIONS_BACKFILLED)).isNotNull()
    }

    private suspend fun createFolderWith(vararg messageIds: String) = backendStorage.run {
        createFolderUpdater().use { it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX))) }

        getFolder(FOLDER_ID).apply {
            for (messageId in messageIds) {
                val message = GraphMessage(id = messageId, receivedDateTime = "2026-01-01T00:00:00Z")
                saveMessage(message.toEnvelopeMessage(), MessageDownloadState.ENVELOPE)
            }
        }
    }

    private fun syncConfig() = SyncConfig(
        expungePolicy = SyncConfig.ExpungePolicy.IMMEDIATELY,
        earliestPollDate = null,
        syncRemoteDeletions = true,
        maximumAutoDownloadMessageSize = 0,
        defaultVisibleLimit = 25,
        syncFlags = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED),
    )

    private class FlagChangeListener : SyncListener {
        val changed = mutableListOf<String>()

        override fun syncStarted(folderServerId: String) = Unit
        override fun syncAuthenticationSuccess() = Unit
        override fun syncHeadersStarted(folderServerId: String) = Unit
        override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
        override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) =
            Unit

        override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit
        override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) = Unit
        override fun syncRemovedMessage(folderServerId: String, messageServerId: String) = Unit
        override fun syncFlagChanged(folderServerId: String, messageServerId: String) {
            changed += messageServerId
        }

        override fun syncFinished(folderServerId: String) = Unit
        override fun syncFailed(folderServerId: String, message: String, exception: Exception?) = Unit
        override fun folderStatusChanged(folderServerId: String) = Unit
    }
}
