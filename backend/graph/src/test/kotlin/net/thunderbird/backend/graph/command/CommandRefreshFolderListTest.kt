package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.FolderType
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
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
                    com.fsck.k9.backend.api.FolderInfo("stale-id", "Gone", FolderType.REGULAR),
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

    private fun enqueueTopLevelFolders(vararg folders: String) {
        server.enqueue(MockResponse().setBody("""{"value":[${folders.joinToString(",")}]}"""))
    }

    /**
     * The well-known folder ids are resolved in one batched request, one entry per name.
     */
    private fun enqueueWellKnownFolders(inboxId: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"responses":[{"id":"0","status":200,"body":{"id":"$inboxId"}}]}""",
            ),
        )
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
