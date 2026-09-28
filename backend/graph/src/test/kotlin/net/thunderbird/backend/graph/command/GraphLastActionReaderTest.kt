package net.thunderbird.backend.graph.command

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.LAST_VERB_EXECUTED_PROPERTY
import net.thunderbird.backend.graph.api.LAST_VERB_FORWARD
import net.thunderbird.backend.graph.api.LAST_VERB_REPLY_TO_SENDER
import net.thunderbird.backend.graph.api.toFlags
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

private const val FOLDER_ID = "inbox-id"

class GraphLastActionReaderTest {
    private val graph = FakeLastActionGraph()
    private val server = MockWebServer().apply { dispatcher = graph }
    private val testSubject = GraphLastActionReader(
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { },
        ),
        logger = TestLogger(),
    )

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the few messages of an incremental round should each be looked up`() {
        graph.verbs["m1"] = LAST_VERB_REPLY_TO_SENDER
        graph.verbs["m2"] = LAST_VERB_FORWARD

        val result = testSubject.withLastActions(FOLDER_ID, messages("m1", "m2", "m3"))

        assertThat(result.map { it.toFlags() })
            .containsExactly(setOf(Flag.ANSWERED), setOf(Flag.FORWARDED), emptySet<Flag>())
        assertThat(server.takeRequest().path).isNotNull().startsWith("/v1.0/\$batch")
    }

    @Test
    fun `the many messages of an initial round should be read from one listing of the folder`() {
        // One lookup per message would be thousands of requests; the folder can say which were replied to.
        graph.verbs["m7"] = LAST_VERB_FORWARD
        val messages = messages(*Array(150) { "m$it" })

        val result = testSubject.withLastActions(FOLDER_ID, messages)

        assertThat(result.filter { it.toFlags().isNotEmpty() }.map { it.id }).containsExactly("m7")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().requestUrl?.encodedPath).isEqualTo("/v1.0/me/mailFolders/$FOLDER_ID/messages")
    }

    @Test
    fun `a refused lookup should leave the messages as they were`() {
        // Only the arrows depend on it; the sync that asked has to carry on.
        graph.isRefusing = true
        val messages = messages("m1")

        val result = testSubject.withLastActions(FOLDER_ID, messages)

        assertThat(result).isEqualTo(messages)
    }

    private fun messages(vararg ids: String) = ids.map { GraphMessage(id = it) }
}

/**
 * Answers last action lookups, whether made message by message in a batch or by listing the folder, from [verbs].
 */
private class FakeLastActionGraph : Dispatcher() {
    val verbs = mutableMapOf<String, Int>()
    var isRefusing = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val isBatch = request.requestUrl?.encodedPath?.endsWith("/\$batch") == true

        return when {
            isRefusing -> MockResponse().setResponseCode(400).setBody("""{"error":{"code":"BadRequest"}}""")
            isBatch -> batchResponse(request.body.readUtf8())
            else -> MockResponse().setBody("""{"value": [${verbs.keys.joinToString(",") { messageBody(it) }}]}""")
        }
    }

    private fun batchResponse(requestBody: String): MockResponse {
        val requests = Json.parseToJsonElement(requestBody).jsonObject.getValue("requests").jsonArray
        val responses = requests.joinToString(",") { item ->
            val id = item.jsonObject.getValue("id").jsonPrimitive.content
            val url = item.jsonObject.getValue("url").jsonPrimitive.content
            val messageId = url.substringAfter("/me/messages/").substringBefore("?")
            """{"id": "$id", "status": 200, "body": ${messageBody(messageId)}}"""
        }

        return MockResponse().setBody("""{"responses": [$responses]}""")
    }

    private fun messageBody(messageId: String): String {
        val properties = verbs[messageId]
            ?.let { """{"id": "$LAST_VERB_EXECUTED_PROPERTY", "value": "$it"}""" }
            .orEmpty()

        return """{"id": "$messageId", "singleValueExtendedProperties": [$properties]}"""
    }
}
