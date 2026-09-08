package com.fsck.k9.ui.messageview

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccountDto
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class DeliveryAddressExtractorTest {

    @Test
    fun `the delivering server's own record should win`() {
        // The only reliable source, and the only one that survives a forward.
        val message = messageTo("someone-else@example.org") {
            setHeader("Delivered-To", "felix+shopping@mine.example")
        }

        assertThat(extract(message)).isEqualTo("felix+shopping@mine.example")
    }

    @Test
    fun `the other delivery header should be read too`() {
        val message = messageTo("someone-else@example.org") {
            setHeader("X-Original-To", "alias@mine.example")
        }

        assertThat(extract(message)).isEqualTo("alias@mine.example")
    }

    @Test
    fun `a plus-addressed recipient should be found without any header`() {
        // What most alias mail looks like, and no delivery header is needed: the address is right there in To,
        // at a domain the reader already has an identity on.
        val message = messageTo("felix+shopping@mine.example")

        assertThat(extract(message)).isEqualTo("felix+shopping@mine.example")
    }

    @Test
    fun `an address at the reader's domain in Cc should count`() {
        val message = messageTo("someone-else@example.org", cc = listOf("felix+news@mine.example"))

        assertThat(extract(message)).isEqualTo("felix+news@mine.example")
    }

    @Test
    fun `the reader's usual address should say nothing`() {
        // Every message would otherwise carry a line stating the obvious, and the line would stop being a
        // signal that anything unusual happened.
        val message = messageTo("felix@mine.example")

        assertThat(extract(message)).isNull()
    }

    @Test
    fun `a delivery header naming the usual address should also say nothing`() {
        val message = messageTo("felix@mine.example") {
            setHeader("Delivered-To", "felix@mine.example")
        }

        assertThat(extract(message)).isNull()
    }

    @Test
    fun `a stranger's address should not be mistaken for the reader's`() {
        // Without a delivery header there is no way to tell the reader's alias on another domain from someone
        // else who was written to as well, so a recipient elsewhere is left alone.
        val message = messageTo("someone-else@example.org")

        assertThat(extract(message)).isNull()
    }

    @Test
    fun `a message with no recipients at all should say nothing`() {
        assertThat(extract(MimeMessage())).isNull()
    }

    @Test
    fun `a delivery header with a display name should yield the address`() {
        val message = messageTo("someone-else@example.org") {
            setHeader("Delivered-To", "Felix <alias@mine.example>")
        }

        assertThat(extract(message)).isEqualTo("alias@mine.example")
    }

    private fun extract(message: Message): String? =
        DeliveryAddressExtractor.extractDeliveryAddress(message, account())

    private fun messageTo(
        vararg to: String,
        cc: List<String> = emptyList(),
        block: MimeMessage.() -> Unit = {},
    ): MimeMessage {
        // Recipients on a MimeMessage are read back out of the headers, so that is how they are set here.
        return MimeMessage().apply {
            setHeader("To", to.joinToString(", "))
            if (cc.isNotEmpty()) {
                setHeader("CC", cc.joinToString(", "))
            }
            block()
        }
    }

    /**
     * One identity, at one domain, which is the shape almost every account has.
     */
    private fun account(): LegacyAccountDto {
        val identity = Identity(email = "felix@mine.example")

        return mock {
            on { identities } doReturn mutableListOf(identity)
            on { isAnIdentity(Address("felix@mine.example")) } doReturn true
        }
    }
}
