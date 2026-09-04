package app.k9mail.legacy.mailstore

import net.thunderbird.feature.mail.message.classification.api.MessageEvidence

/**
 * A stored message, paired with the evidence for classifying it again.
 *
 * Carries the database id rather than the folder and server id, because the only thing done with it is to
 * write the new verdict back onto the same row.
 */
data class StoredClassificationEvidence(
    val messageId: Long,
    val evidence: MessageEvidence,
)
