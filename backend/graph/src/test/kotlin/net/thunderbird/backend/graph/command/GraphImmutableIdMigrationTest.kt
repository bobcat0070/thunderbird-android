package net.thunderbird.backend.graph.command

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
    fun `stored messages should be given the immutable ids Graph lists them under`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-02T00:00:00Z")
        graph.add("old-2", "immutable-2", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1", "old-2")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "immutable-2")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    @Test
    fun `a message should keep what is stored with it`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        folder.setMessageFlag("old-1", Flag.ANSWERED, true)
        folder.setMessageServerCategories("old-1", listOf("Red category"))

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageFlags("immutable-1")).containsExactlyInAnyOrder(Flag.ANSWERED)
        assertThat(folder.getMessageServerCategories("immutable-1")).containsExactly("Red category")
    }

    @Test
    fun `the folder should be listed once with each kind of id`() = runTest {
        // Graph answers a request about one message with the kind of id it was asked with, so asking about a
        // stored message translates nothing. Only a listing hands out the kind of id the request prefers.
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(graph.listings.map { it.immutableIds }).containsExactlyInAnyOrder(false, true)
        assertThat(graph.listings.map { it.folderId }.distinct()).containsExactly(FOLDER_ID)
        assertThat(graph.lookups).isEmpty()
    }

    @Test
    fun `a folder that has been converted should not be listed again`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)
        val requestCount = server.requestCount

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(server.requestCount).isEqualTo(requestCount)
    }

    @Test
    fun `a folder without stored messages should be done without asking Graph anything`() = runTest {
        val folder = createFolderWithStored()

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    @Test
    fun `a message that already has its immutable id should be left alone`() = runTest {
        // Converting again has to be harmless: it is what happens after an interruption, and after a message
        // reached the folder under an old id once the folder had been converted.
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-02T00:00:00Z")
        graph.add("old-2", "immutable-2", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        folder.save("immutable-2", "2026-01-01T00:00:00Z")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "immutable-2")
    }

    @Test
    fun `a message stored under both of its ids should end up stored once`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        folder.save("immutable-1", "2026-01-01T00:00:00Z")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1")
    }

    @Test
    fun `a message Graph no longer has should be removed`() = runTest {
        // Deleted or moved since the last sync. The sync will report that under an id that no longer matches
        // what is stored, so this is the last chance to act on it.
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-02T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        folder.save("gone", "2026-01-01T00:00:00Z")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    @Test
    fun `a message Graph no longer has should be kept when remote deletions are not followed`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-02T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        folder.save("gone", "2026-01-01T00:00:00Z")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = false)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "gone")
    }

    @Test
    fun `a large folder should not be listed to its end for the sake of a deleted message`() = runTest {
        // Three pages of mail on the server, of which only the newest message is stored - beside one that has
        // since been deleted there.
        graph.addMany(count = 1200)
        val folder = createFolderWithStored("old-1")
        folder.save("gone", graph.receivedDateTimeOf("old-2"))

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1")
        assertThat(graph.listings.map { it.skip }).containsExactly(0, 0)
        assertThat(graph.lookups).containsExactly("gone")
    }

    @Test
    fun `stored messages further back than one page should be found on the next`() = runTest {
        graph.addMany(count = 1200)
        val folder = createFolderWithStored("old-1", "old-700")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "immutable-700")
        // Two pages of each listing, and no more: everything stored has been found by then.
        assertThat(graph.listings.map { it.skip }).containsExactly(0, 0, 500, 500)
    }

    @Test
    fun `a stored message that is older than its date suggests should still be found`() = runTest {
        // Stored with a date newer than the one Graph lists it under, as a message with a wrong sent date is.
        // Graph still has it, so the listing is followed until it turns up rather than giving it up as deleted.
        graph.addMany(count = 1200)
        val folder = createFolderWithStored()
        folder.save("old-900", graph.receivedDateTimeOf("old-10"))

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-900")
    }

    @Test
    fun `copies of one mail should each get an id of their own`() = runTest {
        // The same Message-ID received at the same moment: nothing tells the copies apart, so they are paired in
        // the order they are listed.
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z", internetMessageId = "<same>")
        graph.add("old-2", "immutable-2", receivedDateTime = "2026-01-01T00:00:00Z", internetMessageId = "<same>")
        val folder = createFolderWithStored("old-1", "old-2")

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1", "immutable-2")
    }

    @Test
    fun `a listing Graph fails should fail the conversion and leave the folder unconverted`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        graph.isFailing = true

        assertFailsWith<MessagingException> {
            testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)
        }

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("old-1")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isNull()
    }

    @Test
    fun `a conversion that failed should be completed by the next attempt`() = runTest {
        graph.add("old-1", "immutable-1", receivedDateTime = "2026-01-01T00:00:00Z")
        val folder = createFolderWithStored("old-1")
        graph.isFailing = true
        assertFailsWith<MessagingException> { testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true) }
        graph.isFailing = false

        testSubject.migrateIfNeeded(FOLDER_ID, folder, removeMissing = true)

        assertThat(folder.getMessageServerIds()).containsExactlyInAnyOrder("immutable-1")
        assertThat(folder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT)).isEqualTo(ID_FORMAT_IMMUTABLE)
    }

    /**
     * Creates the folder with the given messages stored under their old ids, dated as Graph has them.
     */
    private suspend fun createFolderWithStored(vararg defaultIds: String): BackendFolder {
        backendStorage.createFolderUpdater().use {
            it.createFolders(listOf(FolderInfo(FOLDER_ID, "Inbox", FolderType.INBOX)))
        }

        return backendStorage.getFolder(FOLDER_ID).apply {
            for (defaultId in defaultIds) {
                save(defaultId, graph.receivedDateTimeOf(defaultId))
            }
        }
    }

    private suspend fun BackendFolder.save(messageServerId: String, receivedDateTime: String) {
        val message = GraphMessage(id = messageServerId, receivedDateTime = receivedDateTime)
        saveMessage(message.toEnvelopeMessage(), MessageDownloadState.ENVELOPE)
    }
}

private data class ServerMessage(
    val defaultId: String,
    val immutableId: String,
    val internetMessageId: String,
    val receivedDateTime: String,
)

private data class Listing(val folderId: String, val immutableIds: Boolean, val skip: Int)

/**
 * A mailbox folder that answers the way Microsoft 365 was seen to: a listing hands out the kind of id the request
 * prefers, and a request about one message is answered with the id it was asked with - or 404 for an id no message
 * in the mailbox has.
 */
private class FakeIdGraph : Dispatcher() {
    private val messages = mutableListOf<ServerMessage>()
    val listings = mutableListOf<Listing>()
    val lookups = mutableListOf<String>()
    var isFailing = false

    fun add(defaultId: String, immutableId: String, receivedDateTime: String, internetMessageId: String? = null) {
        messages += ServerMessage(
            defaultId = defaultId,
            immutableId = immutableId,
            internetMessageId = internetMessageId ?: "<$immutableId@example>",
            receivedDateTime = receivedDateTime,
        )
    }

    /**
     * Adds messages `old-1` to `old-<count>`, a minute apart, `old-1` being the newest.
     */
    fun addMany(count: Int) {
        for (number in 1..count) {
            val minutesAgo = number - 1
            val receivedDateTime = "2026-01-%02dT%02d:%02d:00Z".format(
                DAY_OF_NEWEST - minutesAgo / MINUTES_PER_DAY,
                LAST_HOUR - (minutesAgo / MINUTES_PER_HOUR) % HOURS_PER_DAY,
                LAST_MINUTE - minutesAgo % MINUTES_PER_HOUR,
            )
            add("old-$number", "immutable-$number", receivedDateTime)
        }
    }

    fun receivedDateTimeOf(defaultId: String): String = messages.first { it.defaultId == defaultId }.receivedDateTime

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)

        return when {
            isFailing -> MockResponse().setResponseCode(HTTP_SERVER_ERROR)
            url.encodedPath.endsWith("/\$batch") -> batch(request)
            else -> listing(request)
        }
    }

    private fun listing(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        val immutableIds = request.getHeader("Prefer").orEmpty().contains("IdType=\"ImmutableId\"")
        val top = requireNotNull(url.queryParameter("\$top")).toInt()
        val skip = url.queryParameter("\$skip")?.toInt() ?: 0
        listings += Listing(folderId = url.pathSegments[url.pathSegments.size - 2], immutableIds, skip)

        val newestFirst = messages.sortedByDescending { it.receivedDateTime }
        val page = newestFirst.drop(skip).take(top).joinToString(",") { message ->
            val id = if (immutableIds) message.immutableId else message.defaultId
            """{"id":"$id","internetMessageId":"${message.internetMessageId}",""" +
                """"receivedDateTime":"${message.receivedDateTime}"}"""
        }
        val nextLink = if (skip + top < newestFirst.size) {
            val next = url.newBuilder().setQueryParameter("\$skip", (skip + top).toString()).build()
            ""","@odata.nextLink":"$next""""
        } else {
            ""
        }

        return MockResponse().setBody("""{"value":[$page]$nextLink}""")
    }

    private fun batch(request: RecordedRequest): MockResponse {
        val requests = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("requests").jsonArray
        val responses = requests.map { element ->
            val item = element.jsonObject
            val id = item.getValue("id").jsonPrimitive.content
            val askedAbout = item.getValue("url").jsonPrimitive.content
                .substringAfter("/me/messages/")
                .substringBefore("?")
            lookups += askedAbout

            if (messages.any { it.defaultId == askedAbout || it.immutableId == askedAbout }) {
                """{"id":"$id","status":200,"body":{"id":"$askedAbout"}}"""
            } else {
                """{"id":"$id","status":404}"""
            }
        }

        return MockResponse().setBody("""{"responses":[${responses.joinToString(",")}]}""")
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500
        const val DAY_OF_NEWEST = 28
        const val LAST_HOUR = 23
        const val LAST_MINUTE = 59
        const val MINUTES_PER_HOUR = 60
        const val HOURS_PER_DAY = 24
        const val MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY
    }
}
