package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsNone
import assertk.assertions.isEmpty
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

class GraphSyncTest {
    private val server = MockWebServer()
    private val backendStorage = InMemoryBackendStorage()
    private val listener = RecordingSyncListener()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `initial sync should store messages and remember the delta token`() {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m1", subject = "First"), message("m2", subject = "Second")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        val folder = backendStorage.getFolder(FOLDER_ID)
        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("m1", "m2")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_DELTA_LINK)).isNotNull()
        assertThat(listener.failures).isEmpty()
    }

    @Test
    fun `second sync should resume from the stored delta token instead of enumerating again`() {
        createFolder()
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        givenCompletedFullRound(deltaLink)
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(messages = emptyList(), deltaLink = deltaLink),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        // The request must be the stored delta link, not a fresh enumeration of the folder.
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("\$deltatoken")).isEqualTo("t1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `incremental sync should add newly arrived messages`() {
        createFolderWithMessage()
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        givenCompletedFullRound(deltaLink)
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(
                        message("new", subject = "Just arrived", receivedDateTime = "2026-01-02T00:00:00Z"),
                    ),
                    deltaLink = deltaLink,
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds())
            .containsExactlyInAnyOrder("existing", "new")
        assertThat(listener.newMessages).containsExactly("new")
    }

    @Test
    fun `removed messages should be deleted locally`() {
        createFolderWithMessage()
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        givenCompletedFullRound(deltaLink)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "value": [
                    {"id": "existing", "@removed": {"reason": "deleted"}}
                  ],
                  "@odata.deltaLink": "$deltaLink"
                }
                """.trimIndent(),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds()).isEmpty()
        assertThat(listener.removedMessages).containsExactly("existing")
    }

    @Test
    fun `rejected delta token should fall back to a fresh enumeration`() {
        createFolder()
        val staleLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=stale"
        givenCompletedFullRound(staleLink)
        // Graph rejects an expired token; the sync should start over rather than fail.
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":{"code":"resyncRequired"}}"""))
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m1", subject = "First")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=fresh",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds()).containsExactlyInAnyOrder("m1")
        assertThat(listener.failures).isEmpty()
        // Rejected token, window probe, fresh delta round.
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `incomplete round should not store a delta token`() {
        createFolder()
        enqueueWindowProbe()
        // A response with neither a deltaLink nor a nextLink ends the round without a resume point.
        server.enqueue(MockResponse().setBody("""{"value": []}"""))

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getFolderExtraString(FOLDER_EXTRA_DELTA_LINK)).isNull()
    }

    @Test
    fun `remote flag change should be applied locally`() {
        createFolderWithMessage()
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

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageFlags("existing").contains(Flag.SEEN)).isEqualTo(true)
    }

    @Test
    fun `a message with preview text should still be stored as headers-only`() {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m1", subject = "First", bodyPreview = "The first lines of the mail")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        // The preview text reaches the message list either way. What must not happen is the message being
        // called partial: that tells the message view it has enough to show, and the user gets a "download
        // complete message" button instead of the body.
        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageFlags("m1"))
            .containsNone(Flag.X_DOWNLOADED_PARTIAL, Flag.X_DOWNLOADED_FULL)
    }

    @Test
    fun `message without a body preview should still be stored`() {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m1", subject = "First")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds()).containsExactlyInAnyOrder("m1")
    }

    @Test
    fun `asking for more messages should enumerate the folder again instead of resuming`() {
        createFolder()
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        val folder = backendStorage.getFolder(FOLDER_ID)
        folder.setFolderExtraString(FOLDER_EXTRA_DELTA_LINK, deltaLink)
        // The last full round covered 25 messages; the user has since asked for more.
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_LIMIT, "25")
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_FORMAT, SYNC_FORMAT_VERSION.toString())
        folder.visibleLimit = 50
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(messages = listOf(message("m1", subject = "Older")), deltaLink = deltaLink),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        // A fresh enumeration, not a resume: the delta token must not appear in the delta request.
        server.takeRequest() // window probe
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("\$deltatoken")).isNull()
        assertThat(request.path).isNotNull().contains("/messages/delta")
    }

    @Test
    fun `unchanged visible limit should still resume from the stored token`() {
        createFolder()
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        val folder = backendStorage.getFolder(FOLDER_ID)
        folder.setFolderExtraString(FOLDER_EXTRA_DELTA_LINK, deltaLink)
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_LIMIT, "25")
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_FORMAT, SYNC_FORMAT_VERSION.toString())
        folder.visibleLimit = 25
        server.enqueue(
            MockResponse().setBody(deltaResponse(messages = emptyList(), deltaLink = deltaLink)),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(server.takeRequest().requestUrl?.queryParameter("\$deltatoken")).isEqualTo("t1")
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

    @Test
    fun `a format upgrade should not overwrite a message whose body was already downloaded`() {
        // Re-saving writes the envelope over the stored message, so doing it to a message the user has
        // already downloaded would throw the body away and put the download button back.
        createFolderWithMessage()
        val folder = backendStorage.getFolder(FOLDER_ID)
        folder.setMessageFlag("existing", Flag.X_DOWNLOADED_FULL, true)
        val deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1"
        folder.setFolderExtraString(FOLDER_EXTRA_DELTA_LINK, deltaLink)
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_LIMIT, folder.visibleLimit.toString())
        folder.setFolderExtraString(FOLDER_EXTRA_SYNC_FORMAT, "1")
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("existing", subject = "Existing", bodyPreview = "Preview")),
                    deltaLink = deltaLink,
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(folder.getMessageFlags("existing")).contains(Flag.X_DOWNLOADED_FULL)
    }

    @Test
    fun `initial round should page through nextLink so older mail is reachable`() {
        createFolder()
        backendStorage.getFolder(FOLDER_ID).visibleLimit = 3
        enqueueWindowProbe()
        val nextLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$skiptoken=s1"
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "value": [${message("m1", subject = "One")}, ${message("m2", subject = "Two")}],
                  "@odata.nextLink": "$nextLink"
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m3", subject = "Three")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds())
            .containsExactlyInAnyOrder("m1", "m2", "m3")
        // Window probe plus two delta pages.
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `delta request should set the page size by header rather than by a result cap`() {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m1", subject = "One")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        server.takeRequest() // window probe
        val request = server.takeRequest()
        // \$top would cap the whole round and hide older mail, so it must not be used.
        assertThat(request.requestUrl?.queryParameter("\$top")).isNull()
        assertThat(request.getHeader("Prefer")).isEqualTo("odata.maxpagesize=100")
    }

    @Test
    fun `large folder should bound the enumeration by the date of the oldest visible message`() {
        createFolder()
        backendStorage.getFolder(FOLDER_ID).visibleLimit = 2
        // The probe returns a full page, meaning the folder holds more than the window.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "value": [
                    {"id": "a", "receivedDateTime": "2026-01-05T00:00:00Z"},
                    {"id": "b", "receivedDateTime": "2026-01-04T00:00:00Z"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("a", subject = "A")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        server.takeRequest() // window probe
        val deltaRequest = server.takeRequest()
        // Without this bound the round would walk the entire folder to reach a resume token.
        assertThat(deltaRequest.requestUrl?.queryParameter("\$filter"))
            .isEqualTo("receivedDateTime ge 2026-01-04T00:00:00Z")
    }

    @Test
    fun `folder smaller than the window should not be date bounded`() {
        createFolder()
        enqueueWindowProbe()
        server.enqueue(
            MockResponse().setBody(
                deltaResponse(
                    messages = listOf(message("m1", subject = "One")),
                    deltaLink = "${server.url("/v1.0/")}me/mailFolders/$FOLDER_ID/messages/delta?\$deltatoken=t1",
                ),
            ),
        )

        createTestSubject().sync(FOLDER_ID, syncConfig(), listener)

        server.takeRequest() // window probe
        assertThat(server.takeRequest().requestUrl?.queryParameter("\$filter")).isNull()
    }

    /**
     * A fresh enumeration first asks when the oldest message inside the visible window arrived. Returning fewer
     * messages than the limit means the whole folder fits, so no date bound is applied.
     */
    private fun enqueueWindowProbe() {
        server.enqueue(MockResponse().setBody("""{"value": []}"""))
    }

    private fun createFolder() {
        backendStorage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX)))
        }
    }

    private fun createFolderWithMessage() {
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
        )
    }

    private fun syncConfig() = SyncConfig(
        expungePolicy = SyncConfig.ExpungePolicy.IMMEDIATELY,
        earliestPollDate = null,
        syncRemoteDeletions = true,
        maximumAutoDownloadMessageSize = 0,
        defaultVisibleLimit = 25,
        syncFlags = setOf(Flag.SEEN, Flag.FLAGGED),
    )

    private fun message(
        id: String,
        subject: String,
        isRead: Boolean = false,
        receivedDateTime: String = "2026-01-01T12:00:00Z",
        bodyPreview: String? = null,
    ): String {
        val bodyPreviewField = bodyPreview?.let { """"bodyPreview": "$it",""" }.orEmpty()

        return """
            {
              "id": "$id",
              "subject": "$subject",
              "isRead": $isRead,
              "receivedDateTime": "$receivedDateTime",
              $bodyPreviewField
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

        override fun syncFlagChanged(folderServerId: String, messageServerId: String) = Unit
        override fun syncFinished(folderServerId: String) = Unit
        override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
            failures += message
        }

        override fun folderStatusChanged(folderServerId: String) = Unit
    }
}
