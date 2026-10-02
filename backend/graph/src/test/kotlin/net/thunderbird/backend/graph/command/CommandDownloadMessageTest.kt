package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.mail.importance
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.mail.Flag
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private const val FOLDER_ID = "inbox-id"

class CommandDownloadMessageTest {
    private val server = MockWebServer()
    private val backendStorage = InMemoryBackendStorage()

    @BeforeTest
    fun setUp() {
        // Parsing MIME spills large bodies to disk, so the parser needs somewhere to put them.
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloading a complete message should fetch raw MIME and store it as full`() = runTest {
        createFolder()
        server.enqueue(MockResponse().setBody(RAW_MIME))
        server.enqueue(MockResponse().setBody("""{"id":"m1","inferenceClassification":"other"}"""))

        createTestSubject().downloadCompleteMessage(FOLDER_ID, "m1")

        // The $value endpoint returns RFC 5322 content, which the existing MIME parser handles unchanged.
        assertThat(server.takeRequest().path).isNotNull().contains("/me/messages/m1/\$value")
        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageFlags("m1")).contains(Flag.X_DOWNLOADED_FULL)
        // Where Focused Inbox put it, so opening the message does not change how it is classified.
        assertThat(server.takeRequest().requestUrl?.queryParameter("\$select"))
            .isEqualTo("inferenceClassification,importance")
    }

    @Test
    fun `a downloaded message should keep the importance the mailbox holds for it`() = runTest {
        createFolder()
        server.enqueue(MockResponse().setBody(RAW_MIME))
        server.enqueue(MockResponse().setBody("""{"id":"m1","importance":"high"}"""))
        val savedMessages = mutableListOf<Message>()
        val testSubject = createTestSubject(RecordingBackendStorage(backendStorage, savedMessages))

        testSubject.downloadCompleteMessage(FOLDER_ID, "m1")

        assertThat(savedMessages.single().importance).isEqualTo(MessageImportance.HIGH)
    }

    @Test
    fun `downloading structure should store the categories of the message`() = runTest {
        createFolder()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"m1","subject":"Structure only","categories":["Red category"]}""",
            ),
        )

        createTestSubject().downloadMessageStructure(FOLDER_ID, "m1")

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerCategories("m1"))
            .isEqualTo(listOf("Red category"))
    }

    @Test
    fun `downloading structure should store the envelope from the JSON representation`() = runTest {
        createFolder()
        server.enqueue(
            MockResponse().setBody(
                """{"id":"m1","subject":"Structure only","receivedDateTime":"2026-01-01T00:00:00Z"}""",
            ),
        )

        createTestSubject().downloadMessageStructure(FOLDER_ID, "m1")

        val request = server.takeRequest()
        assertThat(request.path).isNotNull().contains("/me/messages/m1")
        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds()).contains("m1")
    }

    @Test
    fun `downloading structure should store the message under the id it was asked for by`() = runTest {
        // Graph answers with the immutable id. In a folder whose stored ids have not been converted yet that is
        // not the id the message is stored under, and saving under it would leave the message there twice.
        createFolder()
        server.enqueue(MockResponse().setBody("""{"id":"immutable-1","subject":"Structure only"}"""))

        createTestSubject().downloadMessageStructure(FOLDER_ID, "old-1")

        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageServerIds()).isEqualTo(setOf("old-1"))
    }

    @Test
    fun `downloaded message should keep the server id as its uid`() = runTest {
        createFolder()
        server.enqueue(MockResponse().setBody(RAW_MIME))

        val message = createTestSubject().fetchFullMessage("m1")

        // Without this the stored message could not be matched back to the server copy.
        assertThat(message.uid).isEqualTo("m1")
        assertThat(message.subject).isEqualTo("Test subject")
    }

    private fun createFolder() {
        backendStorage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX)))
        }
    }

    private fun createTestSubject(backendStorage: BackendStorage = this.backendStorage) = CommandDownloadMessage(
        backendStorage = backendStorage,
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { },
        ),
    )

    private companion object {
        val RAW_MIME = """
            From: sender@example.com
            To: recipient@example.com
            Subject: Test subject
            Content-Type: text/plain; charset=utf-8

            The message body.
        """.trimIndent()
    }
}

/**
 * Hands out folders that note what is saved to them, to see a message the way the command stored it.
 */
private class RecordingBackendStorage(
    private val delegate: BackendStorage,
    private val savedMessages: MutableList<Message>,
) : BackendStorage by delegate {
    override fun getFolder(folderServerId: String): BackendFolder {
        val folder = delegate.getFolder(folderServerId)

        return object : BackendFolder by folder {
            override suspend fun saveMessage(message: Message, downloadState: MessageDownloadState) {
                savedMessages += message
                folder.saveMessage(message, downloadState)
            }
        }
    }
}
