package net.thunderbird.backend.graph.api

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.AddressHeaderBuilder
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.TextBody
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import net.thunderbird.core.common.mail.Flag

/**
 * Builds a [MimeMessage] from the envelope Graph returns for a message.
 *
 * Sync lists messages through the JSON API and only downloads the full MIME content on demand, so the message saved
 * during sync carries just enough for the message list to render. The full content replaces it later.
 *
 * When Graph supplies a body preview it is attached as the message text, because the message list derives its preview
 * line from the first text part. Without it, mail that has never been opened would show no preview at all.
 */
internal fun GraphMessage.toEnvelopeMessage(): MimeMessage {
    val message = MimeMessage()
    message.uid = id

    internetMessageId?.let { message.setHeader("Message-ID", it) }
    subject?.let { message.setSubject(it) }

    (from ?: sender)?.toAddress()?.let { message.setFrom(it) }
    replyTo.toAddressHeaderValue()?.let { message.setHeader("Reply-To", it) }
    toRecipients.toAddressHeaderValue()?.let { message.setHeader("To", it) }
    ccRecipients.toAddressHeaderValue()?.let { message.setHeader("CC", it) }
    bccRecipients.toAddressHeaderValue()?.let { message.setHeader("BCC", it) }

    sentDate()?.let { message.setSentDate(it, false) }

    applyInternetHeaders(message)

    bodyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
        MimeMessageHelper.setBody(message, TextBody(preview))
    }

    for (flag in toFlags()) {
        message.setFlag(flag, true)
    }

    return message
}

/**
 * Headers worth carrying from Graph onto the stored message.
 *
 * Graph returns the full RFC 5322 header block, most of which is routing history that would bloat every row.
 * Only headers the app reads are kept: the ones that say whether a message is bulk, automated or from a mailing
 * list. IMAP already fetches the equivalent subset during envelope sync.
 */
private val RETAINED_HEADERS = setOf(
    "list-unsubscribe",
    "list-unsubscribe-post",
    "list-id",
    "list-post",
    "precedence",
    "auto-submitted",
    "x-auto-response-suppress",
    "return-path",
    // Carries the receiving server's DMARC verdict, which gates whether a sender's brand logo may be shown.
    "authentication-results",
)

/**
 * Copies the retained headers onto the message, so classification sees the same evidence on every backend.
 *
 * Headers may legitimately repeat, so they are added rather than set.
 */
private fun GraphMessage.applyInternetHeaders(message: MimeMessage) {
    internetMessageHeaders
        .mapNotNull { header ->
            val name = header.name?.takeIf { it.lowercase() in RETAINED_HEADERS } ?: return@mapNotNull null
            val value = header.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            name to value
        }
        .forEach { (name, value) -> message.addHeader(name, value) }
}

/**
 * Maps the Graph message state onto the flags the app tracks.
 *
 * Graph has no equivalent of the IMAP `\Answered` flag on the message resource, so replies are not reflected here.
 */
internal fun GraphMessage.toFlags(): Set<Flag> {
    return buildSet {
        if (isRead == true) add(Flag.SEEN)
        if (isDraft == true) add(Flag.DRAFT)
        if (flag?.flagStatus == FLAG_STATUS_FLAGGED) add(Flag.FLAGGED)
    }
}

/**
 * The date used for ordering and for the `Date` header, preferring when the message was sent.
 */
internal fun GraphMessage.sentDate(): Date? {
    return (sentDateTime ?: receivedDateTime)?.toDateOrNull()
}

internal fun GraphMessage.receivedDate(): Date? = receivedDateTime?.toDateOrNull()

/**
 * Parses the ISO-8601 timestamps Graph returns, e.g. `2024-05-06T07:08:09Z`.
 */
private fun String.toDateOrNull(): Date? {
    return try {
        Date.from(Instant.parse(this))
    } catch (e: DateTimeParseException) {
        null
    }
}

private fun GraphRecipient.toAddress(): Address? {
    val address = emailAddress?.address?.takeIf { it.isNotBlank() } ?: return null

    // The address comes from Graph as a bare address, so there is nothing to parse out of it.
    return Address(address, emailAddress.name, false)
}

private fun List<GraphRecipient>.toAddressHeaderValue(): String? {
    val addresses = mapNotNull { it.toAddress() }
    if (addresses.isEmpty()) return null

    return AddressHeaderBuilder.createHeaderValue(addresses.toTypedArray())
}
