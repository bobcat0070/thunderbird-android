package com.fsck.k9.mailstore

import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.SentMailRecipientScanner

/**
 * How many messages must have been sent to an address before it counts as correspondence.
 *
 * One is not enough. Replying once to a marketing address is common, and a single reply would otherwise
 * promote everything that sender ever sends above its own bulk headers. Measured against a real mailbox, this
 * is exactly the line between a support thread someone actually had and a one-off reply to a mailshot.
 */
private const val MINIMUM_MESSAGES_SENT = 2

/**
 * The addresses the user has written to.
 *
 * Someone the user has actually sent mail to is a correspondent, and mail from them is worth reading even
 * when it carries the bulk headers a company mail gateway staples onto everything. This is the strongest
 * evidence available that an address matters to this particular person, and unlike a header it cannot be set
 * by the sender.
 *
 * Answered from the recipient index, which already counts the messages sent to each address - once per message,
 * across every account's sent folder - for address completion. Asking it is one indexed lookup, where reading
 * the sent folders again on each refresh held up every message being saved behind a scan of all of them.
 */
class KnownCorrespondents(
    private val recipientIndex: RecipientIndex,
    private val sentMailRecipientScanner: SentMailRecipientScanner,
) {
    /**
     * @return whether the user has sent mail to [emailAddress].
     */
    fun isKnown(emailAddress: String): Boolean {
        val address = emailAddress.trim().lowercase()
        if (address.isEmpty()) return false

        // Picks up mail sent from elsewhere since the last look; it reads only what is new, and nothing at all
        // when a scan was recent.
        sentMailRecipientScanner.scanIfDue()

        return recipientIndex.timesSentTo(address) >= MINIMUM_MESSAGES_SENT
    }
}
