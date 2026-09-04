package net.thunderbird.backend.graph.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.internet.MessageExtractor
import kotlin.test.Test

class GraphMessageMapperTest {

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
