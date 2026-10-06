package net.thunderbird.backend.graph.command

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.exception.MessagingException
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
    fun `setting categories should patch the whole list`() {
        server.enqueue(batchResponse("""{"id":"0","status":200}"""))

        CommandSetFlag(createClient()).setCategories(listOf("m1"), listOf("Red category", "Project X"))

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"method\":\"PATCH\"")
        assertThat(body).contains("/me/messages/m1")
        assertThat(body).contains("\"categories\":[\"Red category\",\"Project X\"]")
    }

    @Test
    fun `removing every category should patch an empty list`() {
        server.enqueue(batchResponse("""{"id":"0","status":200}"""))

        CommandSetFlag(createClient()).setCategories(listOf("m1"), emptyList())

        assertThat(server.takeRequest().body.readUtf8()).contains("\"categories\":[]")
    }

    @Test
    fun `a flag Graph does not model should not produce a request`() {
        // Deleted has no Graph equivalent, so it is tracked locally only.
        CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.DELETED, newState = true)

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `replying should be recorded the way Outlook records it`() {
        server.enqueue(batchResponse("""{"id":"0","status":200}"""))

        CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.ANSWERED, newState = true)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"Integer 0x1081\"")
        assertThat(body).contains("\"102\"")
        // The icon is what makes Outlook show the replied arrow.
        assertThat(body).contains("\"Integer 0x1080\"")
        assertThat(body).contains("\"261\"")
    }

    @Test
    fun `clearing replied should stay on the device`() {
        // Exchange keeps only the last action and cannot record that none was taken.
        CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.ANSWERED, newState = false)

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
    fun `every request in a batch should ask for immutable ids itself`() {
        // What the batch request prefers does not pass to the requests inside it, and a move answers with the
        // id the message has in its new folder.
        server.enqueue(
            batchResponse(
                """{"id":"0","status":201,"body":{"id":"m1"}}""",
                """{"id":"1","status":201,"body":{"id":"m2"}}""",
            ),
        )

        CommandMoveOrCopy(createClient()).moveMessages("archive", listOf("m1", "m2"))

        val body = server.takeRequest().body.readUtf8()
        assertThat(body.split("\"Prefer\":\"IdType=\\\"ImmutableId\\\"\"")).hasSize(3)
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
    fun `a throttled request inside a batch should be retried`() {
        server.enqueue(
            batchResponse(
                """{"id":"0","status":200}""",
                """{"id":"1","status":429,"headers":{"Retry-After":"1"}}""",
            ),
        )
        server.enqueue(batchResponse("""{"id":"1","status":200}"""))

        CommandSetFlag(createClient()).setFlag(listOf("m1", "m2"), Flag.SEEN, true)

        server.takeRequest()
        val retry = server.takeRequest(1, TimeUnit.SECONDS)?.body?.readUtf8().orEmpty()
        assertThat(retry).contains("/me/messages/m2")
        assertThat(retry.contains("/me/messages/m1")).isEqualTo(false)
    }

    @Test
    fun `a flag change the server failed should be reported so it is tried again`() {
        server.enqueue(batchResponse("""{"id":"0","status":500}"""))

        val exception = assertFailsWith<MessagingException> {
            CommandSetFlag(createClient()).setFlag(listOf("m1"), Flag.SEEN, true)
        }

        assertThat(exception.isPermanentFailure).isEqualTo(false)
    }

    @Test
    fun `deleting a message that is already gone should not fail`() {
        server.enqueue(batchResponse("""{"id":"0","status":404,"body":{"error":{"code":"ErrorItemNotFound"}}}"""))

        CommandDelete(createClient()).deleteMessages(listOf("m1"))

        assertThat(server.requestCount).isEqualTo(1)
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
    fun `searching all folders should search the whole mailbox and group the matches by folder`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"value": [
                  {"id": "d1", "parentFolderId": "inbox-id", "internetMessageId": "<1@x>", "isRead": true},
                  {"id": "d2", "parentFolderId": "archive-id", "internetMessageId": "<2@x>", "isRead": false},
                  {"id": "d3", "parentFolderId": "inbox-id", "internetMessageId": "<3@x>", "isRead": false}
                ]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"value": [
                  {"id": "m1", "parentFolderId": "inbox-id", "internetMessageId": "<1@x>", "isRead": true},
                  {"id": "m2", "parentFolderId": "archive-id", "internetMessageId": "<2@x>", "isRead": false},
                  {"id": "m3", "parentFolderId": "inbox-id", "internetMessageId": "<3@x>", "isRead": false}
                ]}
                """.trimIndent(),
            ),
        )

        val result = CommandSearch(
            createClient(),
        ).searchAllFolders("invoice", requiredFlags = null, forbiddenFlags = null)

        assertThat(result).isEqualTo(mapOf("inbox-id" to listOf("m1", "m3"), "archive-id" to listOf("m2")))
        val request = server.takeRequest().requestUrl
        assertThat(request?.encodedPath).isEqualTo("/v1.0/me/messages")
        assertThat(request?.queryParameter("\$search")).isEqualTo("\"invoice\"")
    }

    @Test
    fun `search matches should be saved under their immutable ids`() {
        // $search answers with default ids however it is asked; saved as they come, every match already on the
        // device was stored a second time.
        server.enqueue(
            MockResponse().setBody(
                """{"value": [{"id": "default-id", "parentFolderId": "inbox-id", "internetMessageId": "<1@x>"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"value": [
                  {"id": "immutable-id", "parentFolderId": "inbox-id", "internetMessageId": "<1@x>"},
                  {"id": "sent-copy", "parentFolderId": "sent-id", "internetMessageId": "<1@x>"}
                ]}
                """.trimIndent(),
            ),
        )

        val result = CommandSearch(createClient()).searchAllFolders("invoice", requiredFlags = null, null)

        // Only the copy that matched: the one in Sent shares its Message-ID but was not found by the search.
        assertThat(result).isEqualTo(mapOf("inbox-id" to listOf("immutable-id")))
        server.takeRequest()
        val lookup = server.takeRequest().requestUrl
        assertThat(lookup?.queryParameter("\$filter")).isEqualTo("internetMessageId eq '<1@x>'")
        assertThat(lookup?.queryParameter("\$search")).isNull()
    }

    @Test
    fun `a search match with no message id should be left out`() {
        // There is nothing to look it up again by, and saved under its default id it would be a duplicate.
        server.enqueue(MockResponse().setBody("""{"value": [{"id": "d1", "parentFolderId": "inbox-id"}]}"""))

        val result = CommandSearch(createClient()).searchAllFolders("invoice", requiredFlags = null, null)

        assertThat(result).isEqualTo(emptyMap())
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a message id containing a quote should be escaped when looked up again`() {
        server.enqueue(
            MockResponse().setBody(
                """{"value": [{"id": "d1", "parentFolderId": "f", "internetMessageId": "<o'brien@x>"}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"value": []}"""))

        CommandSearch(createClient()).searchAllFolders("invoice", requiredFlags = null, forbiddenFlags = null)

        server.takeRequest()
        val filter = server.takeRequest().requestUrl?.queryParameter("\$filter")
        assertThat(filter).isEqualTo("internetMessageId eq '<o''brien@x>'")
    }

    @Test
    fun `searching all folders should still respect the requested flags`() {
        val matches = """
            {"value": [
              {"id": "m1", "parentFolderId": "f", "internetMessageId": "<1@x>", "isRead": true},
              {"id": "m2", "parentFolderId": "f", "internetMessageId": "<2@x>"}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(matches))
        server.enqueue(MockResponse().setBody(matches))

        val result = CommandSearch(createClient())
            .searchAllFolders("invoice", requiredFlags = null, forbiddenFlags = setOf(Flag.SEEN))

        assertThat(result).isEqualTo(mapOf("f" to listOf("m2")))
    }

    @Test
    fun `searching for a sender should list mail from them only`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"value": [{"id": "m1", "parentFolderId": "inbox-id"}, {"id": "m2", "parentFolderId": "misc-id"}]}
                """.trimIndent(),
            ),
        )

        val result = CommandSearch(createClient()).searchAllFoldersFromSender("eva@example.com")

        assertThat(result).isEqualTo(mapOf("inbox-id" to listOf("m1"), "misc-id" to listOf("m2")))
        val request = server.takeRequest().requestUrl
        assertThat(request?.encodedPath).isEqualTo("/v1.0/me/messages")
        // A listing rather than a $search: the address as text also matches mail sent to it, and $search answers
        // with ids the synchronized mail is not stored under.
        assertThat(request?.queryParameter("\$filter")).isEqualTo("from/emailAddress/address eq 'eva@example.com'")
        assertThat(request?.queryParameter("\$search")).isNull()
    }

    @Test
    fun `a sender search should follow the listing to its next page`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"value": [{"id": "m1", "parentFolderId": "inbox-id"}],
                 "@odata.nextLink": "${server.url("/v1.0/me/messages?page=2")}"}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"value": [{"id": "m2", "parentFolderId": "inbox-id"}]}"""))

        val result = CommandSearch(createClient()).searchAllFoldersFromSender("eva@example.com")

        assertThat(result).isEqualTo(mapOf("inbox-id" to listOf("m1", "m2")))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a sender address containing a quote should be escaped for OData`() {
        server.enqueue(MockResponse().setBody("""{"value": []}"""))

        CommandSearch(createClient()).searchAllFoldersFromSender("o'brien@example.com")

        // A bare single quote would end the string literal and let the rest of the address into the filter.
        val filter = server.takeRequest().requestUrl?.queryParameter("\$filter")
        assertThat(filter).isEqualTo("from/emailAddress/address eq 'o''brien@example.com'")
    }

    @Test
    fun `a sender search with no address should not reach the server`() {
        val result = CommandSearch(createClient()).searchAllFoldersFromSender("  ")

        assertThat(result).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a search with no text should be left to the folder by folder search`() {
        val result = CommandSearch(createClient())
            .searchAllFolders(" ", requiredFlags = setOf(Flag.FLAGGED), forbiddenFlags = null)

        assertThat(result).isNull()
        assertThat(server.requestCount).isEqualTo(0)
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
        val matches = """
            {"value":[
              {"id":"read","parentFolderId":"inbox-id","internetMessageId":"<1@x>","isRead":true},
              {"id":"unread","parentFolderId":"inbox-id","internetMessageId":"<2@x>","isRead":false}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(matches))
        server.enqueue(MockResponse().setBody(matches))

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
