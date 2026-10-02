package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.MessageDownloadState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.toEnvelopeMessage
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

private const val FOLDER_ID = "inbox-id"

class GraphImmutableIdMigrationTest {
    private val graph = FakeIdGraph()
    private val server = MockWebServer().apply { dispatcher = graph }
    private val backendStorage = InMemoryBackendStorage()
    private val testSubject = GraphImmutableIdMigration(
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { },
        ),
    )

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stored messages should be given the immutable ids Graph reports for them`() = runTest {
        val folder = createFolderWith("old-1" to "2026-01-02T00:00:00Z", "old-2" to "2026-01-01T00:00:00Z")
        graph.immutableIds["old-1"] = "immutable-1"
        graph.immutableIds["old-2"] = "immutable-2"

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "immutable-2")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    @Test
    fun `a message should keep what is stored with it`() = runTest {
        val folder = createFolderWith("old-1" to "2026-01-01T00:00:00Z")
        folder.setMessageFlag("old-1", Flag.ANSWERED, true)
        folder.setMessageServerCategories("old-1", listOf("Red category"))
        graph.immutableIds["old-1"] = "immutable-1"

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        assertThat(folder.getMessageFlags("immutable-1")).containsExactlyInAnyOrder(Flag.ANSWERED)
        assertThat(folder.getMessageServerCategories("immutable-1")).containsExactly("Red category")
    }

    @Test
    fun `each message should be looked up by the id it is stored under and asked for its immutable id`() = runTest {
        val folder = createFolderWith("old/1" to "2026-01-01T00:00:00Z")
        graph.immutableIds["old/1"] = "immutable-1"

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        val request = graph.batchItems.single()
        assertThat(request.method).isEqualTo("GET")
        // Encoded, so an id containing a slash still names one message.
        assertThat(request.url).isEqualTo("/me/messages/old%2F1?\$select=id")
        assertThat(request.prefer).isEqualTo("IdType=\"ImmutableId\"")
    }

    @Test
    fun `a folder that has been converted should not be asked about again`() = runTest {
        val folder = createFolderWith("old-1" to "2026-01-01T00:00:00Z")
        graph.immutableIds["old-1"] = "immutable-1"
        testSubject.migrateIfNeeded(folder, removeMissing = true)
        val requestCount = server.requestCount

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        assertThat(server.requestCount).isEqualTo(requestCount)
    }

    @Test
    fun `a folder without stored messages should be done without asking Graph anything`() = runTest {
        val folder = createFolderWith()

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    @Test
    fun `a message Graph no longer finds should be removed`() = runTest {
        // Deleted or moved since the last sync. The sync will report that under an id that no longer matches
        // what is stored, so this is the last chance to act on it.
        val folder = createFolderWith("old-1" to "2026-01-02T00:00:00Z", "gone" to "2026-01-01T00:00:00Z")
        graph.immutableIds["old-1"] = "immutable-1"

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    @Test
    fun `a message Graph no longer finds should be kept when remote deletions are not followed`() = runTest {
        val folder = createFolderWith("gone" to "2026-01-01T00:00:00Z")

        testSubject.migrateIfNeeded(folder, removeMissing = false)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("gone")
    }

    @Test
    fun `a message that is already stored under its immutable id should not be stored twice`() = runTest {
        // A message fetched on its own before the folder was converted is stored under its immutable id, beside
        // the copy under the old one.
        val folder = createFolderWith("old-1" to "2026-01-01T00:00:00Z", "immutable-1" to "2026-01-01T00:00:00Z")
        graph.immutableIds["old-1"] = "immutable-1"
        graph.immutableIds["immutable-1"] = "immutable-1"

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1")
    }

    @Test
    fun `a lookup Graph did not answer should fail the conversion and leave the folder unconverted`() = runTest {
        val folder = createFolderWith("old-1" to "2026-01-02T00:00:00Z", "old-2" to "2026-01-01T00:00:00Z")
        graph.immutableIds["old-1"] = "immutable-1"
        graph.failing += "old-2"

        val exception = assertFailsWith<MessagingException> {
            testSubject.migrateIfNeeded(folder, removeMissing = true)
        }

        // Temporary, so the sync is tried again; and what could be converted has been.
        assertThat(exception.isPermanentFailure).isEqualTo(false)
        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "old-2")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isNull()
    }

    @Test
    fun `an interrupted conversion should carry on from where it stopped`() = runTest {
        // More messages than one step converts, so that progress is recorded before the failure.
        val newest = (1..100).map { minute ->
            "new-$minute" to
                "2026-02-01T%02d:%02d:00Z".format(minute / 60, minute % 60)
        }
        val oldest = listOf("old-1" to "2026-01-01T00:00:00Z")
        val folder = createFolderWith(*(newest + oldest).toTypedArray())
        for ((id, _) in newest + oldest) {
            graph.immutableIds[id] = "immutable-$id"
            graph.immutableIds["immutable-$id"] = "immutable-$id"
        }
        graph.failing += "old-1"
        assertFailsWith<MessagingException> { testSubject.migrateIfNeeded(folder, removeMissing = true) }
        assertThat(folder.getFolderExtraNumber(FOLDER_EXTRA_ID_MIGRATION_CURSOR)).isNotNull()
        graph.failing.clear()
        graph.batchItems.clear()

        testSubject.migrateIfNeeded(folder, removeMissing = true)

        // Only the message the first attempt stopped at, which is asked about again, and the one it did not
        // get to - not the ninety-nine before them.
        assertThat(graph.batchItems.map { it.url }).containsExactly(
            "/me/messages/immutable-new-1?\$select=id",
            "/me/messages/old-1?\$select=id",
        )
        assertThat(folder.getMessageServerIds().filter { !it.startsWith("immutable-") }).isEmpty()
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    private suspend fun createFolderWith(vararg messages: Pair<String, String>): BackendFolder {
        backendStorage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX)))
        }

        return backendStorage.getFolder(FOLDER_ID).apply {
            for ((messageId, receivedDateTime) in messages) {
                val message = GraphMessage(id = messageId, receivedDateTime = receivedDateTime)
                saveMessage(message.toEnvelopeMessage(), MessageDownloadState.ENVELOPE)
            }
        }
    }
}

private data class BatchItem(val id: String, val method: String, val url: String, val prefer: String?)

/**
 * Answers the lookups of a batch the way Graph does: a message it knows with its immutable id, one it does not
 * with 404, and one it is failing on with 500.
 */
private class FakeIdGraph : Dispatcher() {
    val immutableIds = mutableMapOf<String, String>()
    val failing = mutableSetOf<String>()
    val batchItems = mutableListOf<BatchItem>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val requests = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("requests").jsonArray
        val items = requests.map { element ->
            val item = element.jsonObject
            BatchItem(
                id = item.getValue("id").jsonPrimitive.content,
                method = item.getValue("method").jsonPrimitive.content,
                url = item.getValue("url").jsonPrimitive.content,
                prefer = item["headers"]?.jsonObject?.get("Prefer")?.jsonPrimitive?.content,
            )
        }
        batchItems += items

        val responses = items.map { item ->
            val storedId = item.url.substringAfter("/me/messages/").substringBefore("?").replace("%2F", "/")
            val immutableId = immutableIds[storedId]

            when {
                storedId in failing -> """{"id":"${item.id}","status":500}"""
                immutableId != null -> """{"id":"${item.id}","status":200,"body":{"id":"$immutableId"}}"""
                else -> """{"id":"${item.id}","status":404}"""
            }
        }

        return MockResponse().setBody("""{"responses":[${responses.joinToString(",")}]}""")
    }
}
