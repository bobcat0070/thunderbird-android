package com.fsck.k9.mailstore

import com.fsck.k9.mail.Message
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence

/**
 * Headers the classifier reads.
 *
 * Listed explicitly rather than passing the whole header block: most of a header block is routing history, and
 * naming the inputs keeps it obvious which headers a backend has to carry for classification to work.
 */
private val CLASSIFICATION_HEADERS = listOf(
    "List-Unsubscribe",
    "List-Id",
    "List-Post",
    "Precedence",
    "Auto-Submitted",
    "X-Auto-Response-Suppress",
)

/**
 * Reduces a parsed message to the facts the classifier is allowed to see.
 */
internal fun Message.toClassificationEvidence(): MessageEvidence {
    val headers = CLASSIFICATION_HEADERS.associate { name ->
        name.lowercase() to getHeader(name).orEmpty().toList()
    }

    return MessageEvidence(
        headers = headers,
        fromAddress = from?.firstOrNull()?.address?.lowercase(),
        recipientCount = recipientCount(),
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
