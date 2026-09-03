package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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
    fun `downloading a complete message should fetch raw MIME and store it as full`() {
        createFolder()
        server.enqueue(MockResponse().setBody(RAW_MIME))

        createTestSubject().downloadCompleteMessage(FOLDER_ID, "m1")

        // The $value endpoint returns RFC 5322 content, which the existing MIME parser handles unchanged.
        assertThat(server.takeRequest().path).isNotNull().contains("/me/messages/m1/\$value")
        assertThat(backendStorage.getFolder(FOLDER_ID).getMessageFlags("m1")).contains(Flag.X_DOWNLOADED_FULL)
    }

    @Test
    fun `downloading structure should store the envelope from the JSON representation`() {
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
    fun `downloaded message should keep the server id as its uid`() {
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

    private fun createTestSubject() = CommandDownloadMessage(
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
