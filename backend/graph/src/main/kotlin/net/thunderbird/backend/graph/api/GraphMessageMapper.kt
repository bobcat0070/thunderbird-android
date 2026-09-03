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

    bodyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
        MimeMessageHelper.setBody(message, TextBody(preview))
    }

    for (flag in toFlags()) {
        message.setFlag(flag, true)
    }

    return message
}

/**
 * Whether the envelope carries a body preview, which makes the saved message partial rather than headers-only.
 */
internal fun GraphMessage.hasBodyPreview(): Boolean = !bodyPreview.isNullOrBlank()

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
