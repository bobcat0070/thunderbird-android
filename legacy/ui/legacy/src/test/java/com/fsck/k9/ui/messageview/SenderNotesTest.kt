package com.fsck.k9.ui.messageview

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.MimeMessage
import org.junit.Test

class SenderNotesTest {

    @Test
    fun `mail carrying list headers should count as a mailing list`() {
        for (header in listOf("List-Id", "List-Post", "List-Unsubscribe")) {
            val testSubject = message(header to "<value@lists.example>")

            assertThat(SenderNotes.isMailingList(testSubject)).isTrue()
        }
    }

    @Test
    fun `bulk mail should count as a mailing list`() {
        val testSubject = message("Precedence" to " Bulk ")

        assertThat(SenderNotes.isMailingList(testSubject)).isTrue()
    }

    @Test
    fun `mail from a person should not count as a mailing list`() {
        val testSubject = message("Precedence" to "first-class")

        assertThat(SenderNotes.isMailingList(testSubject)).isFalse()
    }

    @Test
    fun `the reply addresses should be read from the message`() {
        val testSubject = MimeMessage().apply {
            setReplyTo(Address.parse("Billing <billing@freemail.example>, other@elsewhere.example"))
        }

        assertThat(SenderNotes.replyToAddresses(testSubject))
            .containsExactly("billing@freemail.example", "other@elsewhere.example")
    }

    @Test
    fun `mail from the reader's own address that failed the server's check should be pointed out`() {
        val result = SenderNotes.claimsToBeFromReader(
            isOwnAddress = true,
            senderAddress = "sam@reader.example",
            authenticationResults = listOf("mx.reader.example; spf=fail; dmarc=fail header.from=reader.example"),
            trustedServerId = "mx.reader.example",
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `mail from the reader's own address that passed should not be pointed out`() {
        val result = SenderNotes.claimsToBeFromReader(
            isOwnAddress = true,
            senderAddress = "sam@reader.example",
            authenticationResults = listOf("mx.reader.example; dmarc=pass header.from=reader.example"),
            trustedServerId = "mx.reader.example",
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `a failure reported by another server should not be believed`() {
        // Anyone can add an Authentication-Results header on the way; only the account's own server is believed.
        val result = SenderNotes.claimsToBeFromReader(
            isOwnAddress = true,
            senderAddress = "sam@reader.example",
            authenticationResults = listOf("relay.other.example; dmarc=fail header.from=reader.example"),
            trustedServerId = "mx.reader.example",
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `a failed check for someone else's address is not this warning`() {
        val result = SenderNotes.claimsToBeFromReader(
            isOwnAddress = false,
            senderAddress = "news@shop.example",
            authenticationResults = listOf("mx.reader.example; dmarc=fail header.from=shop.example"),
            trustedServerId = "mx.reader.example",
        )

        assertThat(result).isFalse()
    }

    private fun message(vararg headers: Pair<String, String>) = MimeMessage().apply {
        for ((name, value) in headers) addHeader(name, value)
    }
}
