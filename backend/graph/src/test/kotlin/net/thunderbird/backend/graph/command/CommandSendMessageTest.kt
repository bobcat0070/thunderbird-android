package net.thunderbird.backend.graph.command

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.TextBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64

class CommandSendMessageTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sending should post base64 MIME as text plain`() {
        server.enqueue(MockResponse().setResponseCode(202))

        CommandSendMessage(createClient()).sendMessage(message("Hello there"))

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1.0/me/sendMail")
        // Graph expects raw MIME as base64 declared as text/plain, not as a JSON message object.
        assertThat(request.getHeader("Content-Type")).isNotNull().isEqualTo("text/plain; charset=utf-8")

        val decoded = request.body.readUtf8().decodeBase64()?.utf8()
        assertThat(decoded).isNotNull().transform { it.contains("Hello there") }.isEqualTo(true)
    }

    @Test
    fun `uploading should create the message in the given folder and return its id`() {
        server.enqueue(MockResponse().setBody("""{"id":"created-id"}"""))

        val result = CommandSendMessage(createClient()).uploadMessage("drafts-id", message("Draft body"))

        assertThat(result).isEqualTo("created-id")
        assertThat(server.takeRequest().path).isEqualTo("/v1.0/me/mailFolders/drafts-id/messages")
    }

    @Test
    fun `a message beyond the inline limit should be rejected rather than truncated`() {
        // Graph only accepts MIME inline up to 4 MB; larger messages need an upload session.
        val oversizedMessage = message("x".repeat(5 * 1024 * 1024))

        val exception = assertFailsWith<MessagingException> {
            CommandSendMessage(createClient()).sendMessage(oversizedMessage)
        }

        assertThat(exception.isPermanentFailure).isEqualTo(true)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun message(text: String): MimeMessage {
        return MimeMessage().apply {
            setSubject("Subject")
            MimeMessageHelper.setBody(this, TextBody(text))
        }
    }

    private fun createClient() = GraphApiClient(
        okHttpClient = OkHttpClient(),
        tokenProvider = FakeOAuth2TokenProvider(),
        baseUrl = server.url("/v1.0/").toString(),
        sleeper = { },
    )
}
