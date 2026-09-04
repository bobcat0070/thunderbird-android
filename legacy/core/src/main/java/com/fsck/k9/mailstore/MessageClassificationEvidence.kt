package com.fsck.k9.mailstore

import com.fsck.k9.mail.Message
import net.thunderbird.feature.mail.message.classification.api.CLASSIFICATION_HEADERS
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence

/**
 * Reduces a parsed message to the facts the classifier is allowed to see.
 */
internal fun Message.toClassificationEvidence(
    isKnownContact: Boolean = false,
    hasCorresponded: Boolean = false,
): MessageEvidence {
    val headers = CLASSIFICATION_HEADERS.associate { name ->
        name.lowercase() to getHeader(name).orEmpty().toList()
    }

    return MessageEvidence(
        headers = headers,
        fromAddress = from?.firstOrNull()?.address?.lowercase(),
        recipientCount = recipientCount(),
        isKnownContact = isKnownContact,
        hasCorresponded = hasCorresponded,
    )
}

/**
 * How many addresses the message was visibly addressed to. Bcc is excluded: it is not visible to a recipient
 * and its presence in a stored message says more about how the message was saved than about how it was sent.
 */
private fun Message.recipientCount(): Int {
    return getRecipients(Message.RecipientType.TO).orEmpty().size +
        getRecipients(Message.RecipientType.CC).orEmpty().size
}
