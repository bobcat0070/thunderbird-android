package net.thunderbird.backend.graph.command

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.mail.Flag
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Covers the commands that change server state. They all go through `$batch`, so the assertions focus on what ends up
 * in the batch payload rather than on individual requests.
 */
class GraphCommandTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `setting the read flag should patch isRead`() {
        server.enqueue(batchResponse("""{"id":"0","status":200}"""))

        CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.SEEN, newState = true)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"method\":\"PATCH\"")
        assertThat(body).contains("/me/messages/m1")
        assertThat(body).contains("\"isRead\":true")
    }

    @Test
    fun `clearing the flagged flag should patch the follow up status`() {
        server.enqueue(batchResponse("""{"id":"0","status":200}"""))

        CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.FLAGGED, newState = false)

        assertThat(server.takeRequest().body.readUtf8()).contains("notFlagged")
    }

    @Test
    fun `a flag Graph does not model should not produce a request`() {
        // Answered has no Graph equivalent, so it is tracked locally only.
        CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.ANSWERED, newState = true)

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `marking all as read should patch every unread message in one batch`() {
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"m1"},{"id":"m2"}]}"""))
        server.enqueue(batchResponse("""{"id":"0","status":200}""", """{"id":"1","status":200}"""))

        CommandSetFlag(createClient()).markAllAsRead("inbox-id")

        server.takeRequest() // unread listing
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("/me/messages/m1")
        assertThat(body).contains("/me/messages/m2")
    }

    @Test
    fun `deleting messages should send one batch of deletes`() {
        server.enqueue(batchResponse("""{"id":"0","status":204}""", """{"id":"1","status":204}"""))

        CommandDelete(createClient()).deleteMessages(listOf("m1", "m2"))

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"method\":\"DELETE\"")
        assertThat(body).contains("/me/messages/m1")
        assertThat(body).contains("/me/messages/m2")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `moving messages should report the ids Graph assigned in the destination`() {
        server.enqueue(
            batchResponse(
                """{"id":"0","status":201,"body":{"id":"new-1"}}""",
                """{"id":"1","status":201,"body":{"id":"new-2"}}""",
            ),
        )

        val result = CommandMoveOrCopy(createClient()).moveMessages("archive-id", listOf("m1", "m2"))

        assertThat(result).isEqualTo(mapOf("m1" to "new-1", "m2" to "new-2"))
    }

    @Test
    fun `a message that failed to move should be left out of the mapping`() {
        server.enqueue(
            batchResponse(
                """{"id":"0","status":201,"body":{"id":"new-1"}}""",
                """{"id":"1","status":404,"body":{"error":{"code":"ErrorItemNotFound"}}}""",
            ),
        )

        val result = CommandMoveOrCopy(createClient()).moveMessages("archive-id", listOf("m1", "m2"))

        // Reporting a new id for a message that did not move would make the app lose track of it.
        assertThat(result).isEqualTo(mapOf("m1" to "new-1"))
    }

    @Test
    fun `copying should use the copy action rather than move`() {
        server.enqueue(batchResponse("""{"id":"0","status":201,"body":{"id":"copy-1"}}"""))

        val result = CommandMoveOrCopy(createClient()).copyMessages("archive-id", listOf("m1"))

        assertThat(server.takeRequest().body.readUtf8()).contains("/me/messages/m1/copy")
        assertThat(result).isEqualTo(mapOf("m1" to "copy-1"))
    }

    @Test
    fun `moving and marking as read should patch before moving`() {
        server.enqueue(batchResponse("""{"id":"0","status":200}"""))
        server.enqueue(batchResponse("""{"id":"0","status":201,"body":{"id":"new-1"}}"""))

        CommandMoveOrCopy(createClient()).moveMessagesAndMarkAsRead("archive-id", listOf("m1"))

        assertThat(server.takeRequest().body.readUtf8()).contains("\"isRead\":true")
        assertThat(server.takeRequest().body.readUtf8()).contains("/me/messages/m1/move")
    }

    @Test
    fun `an empty selection should not reach the server`() {
        val client = createClient()

        CommandDelete(client).deleteMessages(emptyList())
        CommandMoveOrCopy(client).moveMessages("archive-id", emptyList())

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `more messages than the batch limit should be split across batches`() {
        val messageServerIds = (1..25).map { "m$it" }
        server.enqueue(batchResponse(*(0..19).map { """{"id":"$it","status":204}""" }.toTypedArray()))
        server.enqueue(batchResponse(*(20..24).map { """{"id":"$it","status":204}""" }.toTypedArray()))

        CommandDelete(createClient()).deleteMessages(messageServerIds)

        // Graph rejects a batch of more than 20 requests.
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().body.readUtf8().split("\"method\"")).hasSize(21)
    }

    @Test
    fun `searching by message id should filter on internetMessageId`() {
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"m1"}]}"""))

        val result = CommandSearch(createClient()).findByMessageId("inbox-id", "<abc@example.com>")

        assertThat(result).isEqualTo("m1")
        val query = server.takeRequest().requestUrl?.queryParameter("\$filter")
        assertThat(query).isNotNull().contains("internetMessageId eq '<abc@example.com>'")
    }

    @Test
    fun `a message id containing a quote should be escaped for OData`() {
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))

        CommandSearch(createClient()).findByMessageId("inbox-id", "o'brien@example.com")

        // A bare single quote would terminate the OData string literal.
        val query = server.takeRequest().requestUrl?.queryParameter("\$filter")
        assertThat(query).isNotNull().contains("o''brien@example.com")
    }

    @Test
    fun `text search should not be combined with a sort Graph rejects`() {
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"m1","isRead":true}]}"""))

        CommandSearch(createClient()).search("inbox-id", "invoice", requiredFlags = null, forbiddenFlags = null)

        val url = server.takeRequest().requestUrl
        assertThat(url?.queryParameter("\$search")).isEqualTo("\"invoice\"")
        // Graph rejects $search combined with $orderby or $filter.
        assertThat(url?.queryParameter("\$orderby")).isNull()
        assertThat(url?.queryParameter("\$filter")).isNull()
    }

    @Test
    fun `flag only search should filter server side`() {
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"m1","isRead":false}]}"""))

        val result = CommandSearch(createClient())
            .search("inbox-id", query = null, requiredFlags = null, forbiddenFlags = setOf(Flag.SEEN))

        assertThat(result).isEqualTo(listOf("m1"))
        assertThat(server.takeRequest().requestUrl?.queryParameter("\$filter")).isEqualTo("isRead eq false")
    }

    @Test
    fun `text search results should still respect the requested flags`() {
        server.enqueue(
            MockResponse().setBody(
                """{"value":[{"id":"read","isRead":true},{"id":"unread","isRead":false}]}""",
            ),
        )

        val result = CommandSearch(createClient())
            .search("inbox-id", "invoice", requiredFlags = null, forbiddenFlags = setOf(Flag.SEEN))

        // Graph cannot express both at once, so the flag condition is applied to the search results.
        assertThat(result).isEqualTo(listOf("unread"))
    }

    @Test
    fun `search with no matches should return nothing`() {
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))

        val result = CommandSearch(createClient())
            .search("inbox-id", "nothing", requiredFlags = null, forbiddenFlags = null)

        assertThat(result).isEmpty()
    }

    private fun createClient() = GraphApiClient(
        okHttpClient = OkHttpClient(),
        tokenProvider = FakeOAuth2TokenProvider(),
        baseUrl = server.url("/v1.0/").toString(),
        sleeper = { },
    )

    private fun batchResponse(vararg responses: String): MockResponse {
        return MockResponse().setBody("""{"responses":[${responses.joinToString(",")}]}""")
    }
}
