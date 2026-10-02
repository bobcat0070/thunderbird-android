package net.thunderbird.backend.graph

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.command.FOLDER_EXTRA_ID_FORMAT
import net.thunderbird.backend.graph.command.ID_FORMAT_IMMUTABLE
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private const val SOURCE_FOLDER_ID = "inbox-id"
private const val TARGET_FOLDER_ID = "archive-id"

class GraphBackendTest {
    private val server = MockWebServer()
    private val backendStorage = InMemoryBackendStorage().apply {
        createFolderUpdater().use {
            it.createFolders(
                listOf(
                    FolderInfo(SOURCE_FOLDER_ID, "Inbox", FolderType.INBOX),
                    FolderInfo(TARGET_FOLDER_ID, "Archive", FolderType.ARCHIVE),
                ),
            )
        }
    }
    private val testSubject = GraphBackend(
        backendStorage = backendStorage,
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { },
        ),
        logger = TestLogger(),
        pushSupport = null,
    )

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `moving out of a folder with old ids should have the destination's ids converted again`() {
        // Graph answers with the kind of id it was asked with, so the message may arrive in the destination under
        // an id of the old kind - where the next sync would report it under its immutable id and store it twice.
        backendStorage.getFolder(TARGET_FOLDER_ID).setFolderExtraString(FOLDER_EXTRA_ID_FORMAT, ID_FORMAT_IMMUTABLE)
        server.enqueue(batchResponse("""{"id":"0","status":201,"body":{"id":"old-2"}}"""))

        testSubject.moveMessages(SOURCE_FOLDER_ID, TARGET_FOLDER_ID, listOf("old-1"))

        assertThat(backendStorage.getFolder(TARGET_FOLDER_ID).getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isNull()
    }

    @Test
    fun `copying out of a folder with old ids should have the destination's ids converted again`() {
        backendStorage.getFolder(TARGET_FOLDER_ID).setFolderExtraString(FOLDER_EXTRA_ID_FORMAT, ID_FORMAT_IMMUTABLE)
        server.enqueue(batchResponse("""{"id":"0","status":201,"body":{"id":"old-2"}}"""))

        testSubject.copyMessages(SOURCE_FOLDER_ID, TARGET_FOLDER_ID, listOf("old-1"))

        assertThat(backendStorage.getFolder(TARGET_FOLDER_ID).getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isNull()
    }

    @Test
    fun `moving out of a converted folder should leave the destination as it is`() {
        backendStorage.getFolder(SOURCE_FOLDER_ID).setFolderExtraString(FOLDER_EXTRA_ID_FORMAT, ID_FORMAT_IMMUTABLE)
        backendStorage.getFolder(TARGET_FOLDER_ID).setFolderExtraString(FOLDER_EXTRA_ID_FORMAT, ID_FORMAT_IMMUTABLE)
        server.enqueue(batchResponse("""{"id":"0","status":201,"body":{"id":"immutable-1"}}"""))

        testSubject.moveMessages(SOURCE_FOLDER_ID, TARGET_FOLDER_ID, listOf("immutable-1"))

        assertThat(backendStorage.getFolder(TARGET_FOLDER_ID).getFolderExtraString(FOLDER_EXTRA_ID_FORMAT))
            .isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    private fun batchResponse(vararg responses: String): MockResponse {
        return MockResponse().setBody("""{"responses":[${responses.joinToString(",")}]}""")
    }
}
