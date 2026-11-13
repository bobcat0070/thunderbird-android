package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.WELL_KNOWN_FOLDERS
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CommandRefreshFolderListTest {
    private val server = MockWebServer()
    private val backendStorage = InMemoryBackendStorage()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `well known folders should be typed and custom folders left regular`() {
        enqueueTopLevelFolders(
            """{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""",
            """{"id":"custom-id","displayName":"Receipts","childFolderCount":0}""",
        )
        enqueueWellKnownFolders(inboxId = "inbox-id")

        createTestSubject().refreshFolderList()

        assertThat(backendStorage.folders.keys).containsExactlyInAnyOrder("inbox-id", "custom-id")
        assertThat(backendStorage.getFolder("inbox-id").type).isEqualTo(FolderType.INBOX)
        assertThat(backendStorage.getFolder("custom-id").type).isEqualTo(FolderType.REGULAR)
    }

    @Test
    fun `nested folders should be fetched and named by their full path`() {
        enqueueTopLevelFolders("""{"id":"parent-id","displayName":"Projects","childFolderCount":1}""")
        // One batched request per level of nesting.
        server.enqueue(
            MockResponse().setBody(
                """
                {"responses":[{"id":"0","status":200,"body":{"value":[
                  {"id":"child-id","displayName":"Alpha","parentFolderId":"parent-id","childFolderCount":0}
                ]}}]}
                """.trimIndent(),
            ),
        )
        enqueueWellKnownFolders(inboxId = "none")

        createTestSubject().refreshFolderList()

        assertThat(backendStorage.folders.keys).containsExactlyInAnyOrder("parent-id", "child-id")
        assertThat(backendStorage.getFolder("child-id").name).isEqualTo("Projects/Alpha")
    }

    @Test
    fun `folders removed on the server should be removed locally`() {
        backendStorage.createFolderUpdater().use {
            it.createFolders(
                listOf(
                    FolderInfo("stale-id", "Gone", FolderType.REGULAR),
                ),
            )
        }
        enqueueTopLevelFolders("""{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""")
        enqueueWellKnownFolders(inboxId = "inbox-id")

        createTestSubject().refreshFolderList()

        assertThat(backendStorage.folders.keys).containsExactlyInAnyOrder("inbox-id")
    }

    @Test
    fun `paged folder listing should be followed`() {
        val nextLink = "${server.url("/v1.0/")}me/mailFolders?\$skiptoken=s1"
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[{"id":"a","displayName":"A","childFolderCount":0}],"@odata.nextLink":"$nextLink"}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody("""{"value":[{"id":"b","displayName":"B","childFolderCount":0}]}"""),
        )
        enqueueWellKnownFolders(inboxId = "a")

        createTestSubject().refreshFolderList()

        assertThat(backendStorage.folders.keys).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `other well known folders should be typed by their ids`() {
        enqueueTopLevelFolders(
            """{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""",
            """{"id":"drafts-id","displayName":"Drafts","childFolderCount":0}""",
        )
        enqueueWellKnownFolders(inboxId = "inbox-id", responses = mapOf("drafts" to (200 to "drafts-id")))

        createTestSubject().refreshFolderList()

        assertThat(backendStorage.getFolder("drafts-id").type).isEqualTo(FolderType.DRAFTS)
    }

    @Test
    fun `a failed inbox lookup should fail the refresh and leave the stored folders as they were`() {
        // Seen during a Microsoft outage: the batch went through, but the lookup of the Inbox inside it failed.
        createStoredFolders("inbox-id" to FolderType.INBOX)
        enqueueTopLevelFolders("""{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""")
        enqueueWellKnownFolders(inboxId = "inbox-id", responses = mapOf("inbox" to (500 to null)))

        assertFailure { createTestSubject().refreshFolderList() }.isInstanceOf<MessagingException>()

        assertThat(backendStorage.getFolder("inbox-id").type).isEqualTo(FolderType.INBOX)
    }

    @Test
    fun `an inbox reported as not found should fail the refresh`() {
        createStoredFolders("inbox-id" to FolderType.INBOX)
        enqueueTopLevelFolders("""{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""")
        enqueueWellKnownFolders(inboxId = "inbox-id", responses = mapOf("inbox" to (404 to null)))

        assertFailure { createTestSubject().refreshFolderList() }.isInstanceOf<MessagingException>()

        assertThat(backendStorage.getFolder("inbox-id").type).isEqualTo(FolderType.INBOX)
    }

    @Test
    fun `a failed lookup of another well known folder should fail the refresh`() {
        createStoredFolders("inbox-id" to FolderType.INBOX, "drafts-id" to FolderType.DRAFTS)
        enqueueTopLevelFolders(
            """{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""",
            """{"id":"drafts-id","displayName":"Drafts","childFolderCount":0}""",
        )
        enqueueWellKnownFolders(inboxId = "inbox-id", responses = mapOf("drafts" to (500 to null)))

        assertFailure { createTestSubject().refreshFolderList() }.isInstanceOf<MessagingException>()

        assertThat(backendStorage.getFolder("drafts-id").type).isEqualTo(FolderType.DRAFTS)
    }

    @Test
    fun `failed child folder listing should fail the refresh and keep the stored child folders`() {
        createStoredFolders("parent-id" to FolderType.REGULAR, "child-id" to FolderType.REGULAR)
        enqueueTopLevelFolders("""{"id":"parent-id","displayName":"Projects","childFolderCount":1}""")
        server.enqueue(MockResponse().setBody("""{"responses":[{"id":"0","status":500,"body":{}}]}"""))
        enqueueWellKnownFolders(inboxId = "none")

        assertFailure { createTestSubject().refreshFolderList() }.isInstanceOf<MessagingException>()

        assertThat(backendStorage.folders.keys).containsExactlyInAnyOrder("parent-id", "child-id")
    }

    @Test
    fun `a parent deleted after it was listed should have no child folders`() {
        enqueueTopLevelFolders(
            """{"id":"inbox-id","displayName":"Inbox","childFolderCount":0}""",
            """{"id":"gone-id","displayName":"Gone","childFolderCount":1}""",
        )
        server.enqueue(MockResponse().setBody("""{"responses":[{"id":"0","status":404,"body":{}}]}"""))
        enqueueWellKnownFolders(inboxId = "inbox-id")

        createTestSubject().refreshFolderList()

        assertThat(backendStorage.folders.keys).containsExactlyInAnyOrder("inbox-id", "gone-id")
    }

    private fun enqueueTopLevelFolders(vararg folders: String) {
        server.enqueue(MockResponse().setBody("""{"value":[${folders.joinToString(",")}]}"""))
    }

    /**
     * The well-known folder ids are resolved in one batched request, one entry per name in [WELL_KNOWN_FOLDERS] order.
     * Folders other than the Inbox are not found unless [responses] says otherwise.
     *
     * @param responses the status and folder id to answer for a well-known name, by name.
     */
    private fun enqueueWellKnownFolders(
        inboxId: String,
        responses: Map<String, Pair<Int, String?>> = emptyMap(),
    ) {
        val answers = WELL_KNOWN_FOLDERS.keys.mapIndexed { index, name ->
            val (status, id) = responses[name] ?: if (name == "inbox") 200 to inboxId else 404 to null
            val body = if (id != null) """{"id":"$id"}""" else """{"error":{"code":"ErrorItemNotFound"}}"""
            """{"id":"$index","status":$status,"body":$body}"""
        }
        server.enqueue(MockResponse().setBody("""{"responses":[${answers.joinToString(",")}]}"""))
    }

    private fun createStoredFolders(vararg folders: Pair<String, FolderType>) {
        backendStorage.createFolderUpdater().use { updater ->
            updater.createFolders(folders.map { (id, type) -> FolderInfo(id, id, type) })
        }
    }

    private fun createTestSubject() = CommandRefreshFolderList(
        backendStorage = backendStorage,
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { },
        ),
    )
}
