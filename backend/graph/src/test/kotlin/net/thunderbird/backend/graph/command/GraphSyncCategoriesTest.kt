package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
import net.thunderbird.backend.graph.api.toEnvelopeMessage
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private const val FOLDER_ID = "inbox-id"

/**
 * How a sync keeps the Outlook categories of stored messages in step with the mailbox.
 */
class GraphSyncCategoriesTest {
    private val server = MockWebServer()
    private val backendStorage = InMemoryBackendStorage()
    private val listener = RecordingSyncListener()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the categories of a new message should be stored with it`() = runTest {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(
                        message("m1", subject = "Labelled", categories = listOf("Red category", "Project X")),
                        message("m2", subject = "Plain", categories = emptyList()),
                    ),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        val folder = backendStorage.getFolder(FOLDER_ID)
        assertThat(folder.getMessageServerCategories("m1")).isEqualTo(listOf("Red category", "Project X"))
        assertThat(folder.getMessageServerCategories("m2")).isEmpty()
    }

    @Test
    fun `a category assigned in Outlook should reach a message that is already stored`() = runTest {
        createFolderWithMessage()
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        givenCompletedFullRound(deltaLink)
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("existing", subject = "Existing", categories = listOf("Blue category"))),
                    deltaLink = deltaLink,
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerCategories("existing"))
            .isEqualTo(listOf("Blue category"))
        assertThat(listener.changedMessages).containsExactly("existing")
    }

    @Test
    fun `a category removed in Outlook should be removed from the stored message`() = runTest {
        createFolderWithMessage()
        backendStorage.getFolder(FOLDER_ID).setMessageServerCategories("existing", listOf("Blue category"))
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        givenCompletedFullRound(deltaLink)
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("existing", subject = "Existing", categories = emptyList())),
                    deltaLink = deltaLink,
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerCategories("existing")).isEmpty()
    }

    @Test
    fun `a change that does not mention categories should leave the stored ones alone`() = runTest {
        createFolderWithMessage()
        backendStorage.getFolder(FOLDER_ID).setMessageServerCategories("existing", listOf("Blue category"))
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        givenCompletedFullRound(deltaLink)
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("existing", subject = "Existing", isRead = true)),
                    deltaLink = deltaLink,
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerCategories("existing"))
            .isEqualTo(listOf("Blue category"))
    }

    @Test
    fun `sync should ask for importance and categories`() = runTest {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = emptyList(),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        server.takeRequest() // window probe
        val select = server.takeRequest().requestUrl?.queryParameter("\$select").orEmpty().split(",")
        assertThat(select).contains("importance")
        assertThat(select).contains("categories")
    }

    /**
     * Puts a folder in the state it would be in after a completed full round: a resume token plus the limit it
     * covered.
     */
    private fun givenCompletedFullRound(deltaLink: String) {
        val folder = backendStorage.getFolder(FOLDER_ID)
        folder.setFolderExtraString(FOLDER_EXTRA_DELTA_LINK, deltaLink)
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_LIMIT, folder.visibleLimit.toString())
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_FORMAT, SYNC_FORMAT_VERSION.toString())
    }

    private fun enqueueWindowProbe(edgeReceivedDateTime: String? = null) {
        val edge = edgeReceivedDateTime?.let { """{"id": "edge", "receivedDateTime": "$it"}""" }.orEmpty()
        server.enqueue(MockResponse().setBody("""{"value": [$edge]}"""))
    }

    private fun createFolder() {
        backendStorage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX)))
        }
    }

    private suspend fun createFolderWithMessage() {
        createFolder()
        val folder = backendStorage.getFolder(FOLDER_ID)
        val existingMessage = GraphMessage(
            id = "existing",
            receivedDateTime = "2026-01-01T00:00:00Z",
            subject = "Existing",
        )
        folder.saveMessage(existingMessage.toEnvelopeMessage(), MessageDownloadState.ENVELOPE)
    }

    private fun createTestSubject(): GraphSync {
        return GraphSync(
            backendStorage = backendStorage,
            client = GraphApiClient(
                okHttpClient = OkHttpClient(),
                tokenProvider = FakeOAuth2TokenProvider(),
                baseUrl = server.url("/v1.0/").toString(),
                sleeper = { },
            ),
            logger = TestLogger(),
            // Covered by GraphLastActionReaderTest; here the round's messages carry whatever the test put in them.
            readLastActions = { _, messages -> messages },
            backfillLastActions = { _, _, _, _ -> },
        )
    }

    private fun syncConfig(defaultVisibleLimit: Int = 25) = SyncConfig(
        expungePolicy = SyncConfig.ExpungePolicy.IMMEDIATELY,
        earliestPollDate = null,
        syncRemoteDeletions = true,
        maximumAutoDownloadMessageSize = 0,
        defaultVisibleLimit = defaultVisibleLimit,
        syncFlags = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED),
    )

    private fun message(
        id: String,
        subject: String,
        isRead: Boolean = false,
        receivedDateTime: String = "2026-01-01T12:00:00Z",
        bodyPreview: String? = null,
        categories: List<String>? = null,
    ): String {
        val bodyPreviewField = bodyPreview?.let { """"bodyPreview": "$it",""" }.orEmpty()
        val categoriesField = categories
            ?.let { names -> """"categories": [${names.joinToString(",") { "\"$it\"" }}],""" }
            .orEmpty()

        return """
            {
              "id": "$id",
              "subject": "$subject",
              "isRead": $isRead,
              "receivedDateTime": "$receivedDateTime",
              $bodyPreviewField
              $categoriesField
              "from": {"emailAddress": {"name": "Sender", "address": "sender@example.com"}}
            }
        """.trimIndent()
    }

    private fun deltaResponse(messages: List<String>, deltaLink: String): String {
        return """
            {
              "value": [${messages.joinToString(",")}],
              "@odata.deltaLink": "$deltaLink"
            }
        """.trimIndent()
    }

    private class RecordingSyncListener : SyncListener {
        val newMessages = mutableListOf<String>()
        val removedMessages = mutableListOf<String>()
        val changedMessages = mutableListOf<String>()
        val failures = mutableListOf<String>()

        override fun syncStarted(folderServerId: String) = Unit
        override fun syncAuthenticationSuccess() = Unit
        override fun syncHeadersStarted(folderServerId: String) = Unit
        override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) = Unit
        override fun syncHeadersFinished(
            folderServerId: String,
            totalMessagesInMailbox: Int,
            numNewMessages: Int,
        ) = Unit

        override fun syncProgress(folderServerId: String, completed: Int, total: Int) = Unit
        override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) {
            newMessages += messageServerId
        }

        override fun syncRemovedMessage(folderServerId: String, messageServerId: String) {
            removedMessages += messageServerId
        }

        override fun syncFlagChanged(folderServerId: String, messageServerId: String) {
            changedMessages += messageServerId
        }

        override fun syncFinished(folderServerId: String) = Unit
        override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
            failures += message
        }

        override fun folderStatusChanged(folderServerId: String) = Unit
    }
}
