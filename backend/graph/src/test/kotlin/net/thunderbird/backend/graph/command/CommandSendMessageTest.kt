package net.thunderbird.backend.graph.command

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.TextBody
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

private const val CRLF = "\r\n"

class CommandSendMessageTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        // The app points this at its cache at startup; parsing a message with attachments writes them there.
        BinaryTempFileBody.setTempDirectory(createTempDirectory().toFile())
    }

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
        // Attachments can be moved out of the way, but text this large has nowhere to go.
        val oversizedMessage = message("x".repeat(5 * 1024 * 1024))

        val exception = assertFailsWith<MessagingException> {
            CommandSendMessage(createClient()).sendMessage(oversizedMessage)
        }

        assertThat(exception.isPermanentFailure).isEqualTo(true)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a large attachment should go through an upload session and the draft then be sent`() {
        val attachment = ByteArray(MAX_DIRECT_ATTACHMENT_BYTES + 1000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody("""{"id":"draft-1"}"""))
        server.enqueue(MockResponse().setBody("""{"uploadUrl":"${server.url("/upload/session-1")}"}"""))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(202))

        smallLimitSender().sendMessage(messageWithAttachment(attachment))

        val create = server.takeRequest()
        assertThat(create.path).isEqualTo("/v1.0/me/messages")
        val createdMime = create.body.readUtf8().decodeBase64()?.utf8().orEmpty()
        // The draft keeps its text and headers but not the file, which follows separately.
        assertThat(createdMime.contains("Body text")).isEqualTo(true)
        assertThat(createdMime.contains("report.bin")).isEqualTo(false)

        val session = server.takeRequest()
        assertThat(session.path).isEqualTo("/v1.0/me/messages/draft-1/attachments/createUploadSession")
        assertThat(session.body.readUtf8().contains("report.bin")).isEqualTo(true)

        val upload = server.takeRequest()
        assertThat(upload.method).isEqualTo("PUT")
        assertThat(upload.getHeader("Content-Range")).isEqualTo("bytes 0-${attachment.size - 1}/${attachment.size}")
        // The upload URL authorizes itself; the account's token must not go along with it.
        assertThat(upload.getHeader("Authorization")).isNull()
        assertThat(upload.body.readByteArray().contentEquals(attachment)).isEqualTo(true)

        assertThat(server.takeRequest().path).isEqualTo("/v1.0/me/messages/draft-1/send")
    }

    @Test
    fun `a small attachment moved out of the way should be added in one request`() {
        server.enqueue(MockResponse().setBody("""{"id":"draft-1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(202))

        smallLimitSender().sendMessage(messageWithAttachment(ByteArray(6000)))

        server.takeRequest()
        val attachmentRequest = server.takeRequest()
        assertThat(attachmentRequest.path).isEqualTo("/v1.0/me/messages/draft-1/attachments")
        assertThat(attachmentRequest.body.readUtf8().contains("\"contentBytes\"")).isEqualTo(true)
        assertThat(server.takeRequest().path).isEqualTo("/v1.0/me/messages/draft-1/send")
    }

    @Test
    fun `a draft whose attachment could not be added should be deleted`() {
        server.enqueue(MockResponse().setBody("""{"id":"draft-1"}"""))
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(204))

        assertFailsWith<MessagingException> {
            smallLimitSender().sendMessage(messageWithAttachment(ByteArray(6000)))
        }

        server.takeRequest()
        server.takeRequest()
        val cleanup = server.takeRequest()
        assertThat(cleanup.method).isEqualTo("DELETE")
        assertThat(cleanup.path).isEqualTo("/v1.0/me/messages/draft-1")
    }

    @Test
    fun `an upload URL on a host that is not Microsoft's should be refused`() {
        server.enqueue(MockResponse().setBody("""{"id":"draft-1"}"""))
        server.enqueue(MockResponse().setBody("""{"uploadUrl":"https://uploads.example.net/session"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val attachment = ByteArray(MAX_DIRECT_ATTACHMENT_BYTES + 1000)

        assertFailsWith<MessagingException> {
            smallLimitSender().sendMessage(messageWithAttachment(attachment))
        }

        server.takeRequest()
        server.takeRequest()
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    /**
     * A sender whose one-request limit is small, so a test does not need megabytes of message to go past it.
     */
    private fun smallLimitSender() = CommandSendMessage(createClient(), maxInlineMimeBytes = 4000)

    private fun messageWithAttachment(attachment: ByteArray): MimeMessage {
        val encoded = attachment.toByteString().base64().chunked(76).joinToString(CRLF)
        val mime = listOf(
            "From: me@example.com",
            "To: you@example.com",
            "Subject: Report",
            "In-Reply-To: <original@example.com>",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"b1\"",
            "",
            "--b1",
            "Content-Type: text/plain; charset=utf-8",
            "",
            "Body text",
            "--b1",
            "Content-Type: application/octet-stream; name=\"report.bin\"",
            "Content-Disposition: attachment; filename=\"report.bin\"",
            "Content-Transfer-Encoding: base64",
            "",
            encoded,
            "--b1--",
            "",
        ).joinToString(CRLF)

        return MimeMessage.parseMimeMessage(mime.byteInputStream(), true)
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
