package net.thunderbird.feature.mail.message.classification.internal

import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.MessageClassification
import net.thunderbird.feature.mail.message.classification.api.MessageClassifier
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence

/**
 * Local parts that conventionally mean "this mailbox does not read replies".
 *
 * Matched as whole words against the address local part rather than as substrings, so a person named
 * `alerts.morgan@` or a team mailbox at `noreplyagency.com` is not swept up.
 */
private val NO_REPLY_LOCAL_PARTS = setOf(
    "noreply",
    "no-reply",
    "no_reply",
    "donotreply",
    "do-not-reply",
    "do_not_reply",
    "notification",
    "notifications",
    "mailer-daemon",
    "postmaster",
    "bounce",
    "bounces",
)

/**
 * `Precedence` values that indicate mail sent to many recipients.
 */
private val BULK_PRECEDENCE = setOf("bulk", "list")

/**
 * Classifies mail from the headers it carries.
 *
 * Rules rather than a model, because senders of bulk and automated mail label themselves: the standards exist
 * precisely so that clients can recognise this mail, and large providers now require the labelling. A rule is
 * also able to say which header decided, which a model cannot.
 *
 * The order below is not arbitrary. It was chosen against a real mailbox where `List-Unsubscribe` appeared on
 * 58% of messages, `Precedence` on 7%, and `Auto-Submitted` on exactly one, so the ordering has to work when
 * several signals appear at once and when the strongest ones are absent entirely.
 */
class RuleBasedMessageClassifier : MessageClassifier {

    override fun classify(evidence: MessageEvidence): MessageClassification {
        return mailingList(evidence)
            ?: automated(evidence)
            ?: bulk(evidence)
            ?: noReplySender(evidence)
            ?: MessageClassification.UNKNOWN
    }

    /**
     * A real mailing list, which outranks everything else: list traffic is written by people even though it
     * arrives in bulk, and it is the one kind of bulk mail where replying is normal.
     */
    private fun mailingList(evidence: MessageEvidence): MessageClassification? {
        if (!evidence.hasHeader("list-id") && !evidence.hasHeader("list-post")) return null

        return MessageClassification(MessageClass.NEWSLETTER, ClassificationSignal.MAILING_LIST)
    }

    /**
     * Machine-generated mail, checked before the bulk headers.
     *
     * Transactional mail increasingly carries `List-Unsubscribe` as well, because bulk sender requirements
     * ask for it, so testing the bulk headers first would file receipts and alerts as newsletters.
     */
    private fun automated(evidence: MessageEvidence): MessageClassification? {
        val autoSubmitted = evidence.firstHeader("auto-submitted")?.trim()?.lowercase()
        if (autoSubmitted != null && autoSubmitted != "no") {
            return MessageClassification(MessageClass.NOTIFICATION, ClassificationSignal.AUTO_SUBMITTED)
        }

        if (evidence.hasHeader("x-auto-response-suppress")) {
            return MessageClassification(
                MessageClass.NOTIFICATION,
                ClassificationSignal.AUTO_RESPONSE_SUPPRESSED,
            )
        }

        return null
    }

    private fun bulk(evidence: MessageEvidence): MessageClassification? {
        val precedence = evidence.firstHeader("precedence")?.trim()?.lowercase()

        if (evidence.hasHeader("list-unsubscribe") || precedence in BULK_PRECEDENCE) {
            return MessageClassification(MessageClass.NEWSLETTER, ClassificationSignal.BULK_HEADER)
        }

        return null
    }

    /**
     * The weakest signal, and the only one that reads the address rather than a header the sender set
     * deliberately. It runs last so anything better decides first.
     */
    private fun noReplySender(evidence: MessageEvidence): MessageClassification? {
        val localPart = evidence.fromAddress?.substringBefore('@')?.lowercase() ?: return null
        if (!localPart.hasNoReplyPart()) return null

        return MessageClassification(MessageClass.NOTIFICATION, ClassificationSignal.NO_REPLY_SENDER)
    }

    /**
     * Splits the local part on the separators senders actually use, so `no-reply`, `jira.noreply` and
     * `bounces+tag` all match while `noreplacement` does not.
     */
    private fun String.hasNoReplyPart(): Boolean {
        if (this in NO_REPLY_LOCAL_PARTS) return true

        return split('.', '+', '=', '_').any { it in NO_REPLY_LOCAL_PARTS } ||
            NO_REPLY_LOCAL_PARTS.any { this.startsWith("$it-") || this.startsWith("$it.") }
    }
}
