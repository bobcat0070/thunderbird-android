package net.thunderbird.backend.graph.api

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.mail.importance
import com.fsck.k9.mail.internet.MessageExtractor
import java.time.Instant
import kotlin.test.Test
import net.thunderbird.core.common.mail.Flag

class GraphMessageMapperTest {

    @Test
    fun `when the mailbox received a message should be recorded as its internal date`() {
        val envelope = GraphMessage(
            id = "m1",
            sentDateTime = "2026-10-01T23:00:00Z",
            receivedDateTime = "2026-10-02T08:30:00Z",
        ).toEnvelopeMessage()

        assertThat(envelope.internalDate?.time).isEqualTo(Instant.parse("2026-10-02T08:30:00Z").toEpochMilli())
    }

    @Test
    fun `where Focused Inbox sorted a message should be recorded on it`() {
        val envelope = GraphMessage(id = "m1", inferenceClassification = "Other").toEnvelopeMessage()

        assertThat(envelope.getHeader("X-Thunderbird-Server-Relevance").toList()).isEqualTo(listOf("other"))
    }

    @Test
    fun `a sender's own copy of the relevance header should be replaced`() {
        val message = com.fsck.k9.mail.internet.MimeMessage().apply {
            addHeader("X-Thunderbird-Server-Relevance", "focused")
        }

        message.setServerRelevance("other")

        assertThat(message.getHeader("X-Thunderbird-Server-Relevance").toList()).isEqualTo(listOf("other"))
    }

    @Test
    fun `the importance Graph reports should be stated in the headers of the envelope`() {
        val envelope = GraphMessage(id = "m1", importance = "high").toEnvelopeMessage()

        assertThat(envelope.importance).isEqualTo(MessageImportance.HIGH)
    }

    @Test
    fun `the importance Graph reports should replace what the headers said`() {
        val message = com.fsck.k9.mail.internet.MimeMessage().apply {
            addHeader("Importance", "high")
            addHeader("X-Priority", "1")
        }

        message.setServerImportance("normal")

        assertThat(message.importance).isEqualTo(MessageImportance.NORMAL)
    }

    @Test
    fun `importance Graph did not send should leave the headers alone`() {
        val message = com.fsck.k9.mail.internet.MimeMessage().apply {
            addHeader("Importance", "low")
        }

        message.setServerImportance(null)

        assertThat(message.importance).isEqualTo(MessageImportance.LOW)
    }

    @Test
    fun `categories should be trimmed and listed once`() {
        val message = GraphMessage(id = "m1", categories = listOf(" Red category ", "", "Project X", "Project X"))

        assertThat(message.serverCategories()).isEqualTo(listOf("Red category", "Project X"))
    }

    @Test
    fun `categories Graph did not send should not read as none`() {
        assertThat(GraphMessage(id = "m1").serverCategories()).isNull()
    }

    @Test
    fun `a message Outlook recorded as replied to should be answered`() {
        val message = GraphMessage(
            id = "m1",
            isRead = true,
            singleValueExtendedProperties = listOf(GraphExtendedProperty(id = "Integer 0x1081", value = "103")),
        )

        assertThat(message.toFlags()).containsOnly(Flag.SEEN, Flag.ANSWERED)
    }

    @Test
    fun `a message Outlook recorded as forwarded should be forwarded`() {
        val message = GraphMessage(
            id = "m1",
            singleValueExtendedProperties = listOf(GraphExtendedProperty(id = "integer 0x1081", value = "104")),
        )

        assertThat(message.toFlags()).containsOnly(Flag.FORWARDED)
    }

    @Test
    fun `body preview should become the message text so the list can show a preview`() {
        val graphMessage = GraphMessage(
            id = "m1",
            subject = "Subject",
            bodyPreview = "The first lines of the mail",
        )

        val message = graphMessage.toEnvelopeMessage()

        assertThat(MessageExtractor.getTextFromPart(message)).isEqualTo("The first lines of the mail")
    }

    @Test
    fun `message without a body preview should have no body`() {
        val graphMessage = GraphMessage(id = "m1", subject = "Subject")

        val message = graphMessage.toEnvelopeMessage()

        assertThat(message.body).isNull()
    }

    @Test
    fun `blank body preview should not produce a body`() {
        val graphMessage = GraphMessage(id = "m1", subject = "Subject", bodyPreview = "   ")

        val message = graphMessage.toEnvelopeMessage()

        assertThat(message.body).isNull()
    }

    @Test
    fun `envelope should carry sender and recipients`() {
        val graphMessage = GraphMessage(
            id = "m1",
            subject = "Subject",
            from = GraphRecipient(GraphEmailAddress(name = "Sender", address = "sender@example.com")),
            toRecipients = listOf(GraphRecipient(GraphEmailAddress(name = "Rec", address = "rec@example.com"))),
        )

        val message = graphMessage.toEnvelopeMessage()

        assertThat(message.from.first().address).isEqualTo("sender@example.com")
        assertThat(message.getRecipients(com.fsck.k9.mail.Message.RecipientType.TO).first().address)
            .isEqualTo("rec@example.com")
    }
}
