package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageStore
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.mailstore.StoredClassificationEvidence
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.mail.message.classification.api.CLASSIFIER_VERSION
import net.thunderbird.feature.mail.message.classification.api.MessageClassification
import net.thunderbird.feature.mail.message.classification.api.MessageClassifier
import net.thunderbird.legacy.logging.Log

/**
 * How many messages are classified per database round trip.
 *
 * Small enough that a batch's evidence is never a large allocation, large enough that a mailbox of tens of
 * thousands is not thousands of transactions.
 */
private const val BATCH_SIZE = 200

/**
 * Higher than any classifier version, so a forced pass matches every stored message rather than only those
 * behind the current rules.
 */
private const val EVERY_VERSION = Int.MAX_VALUE

/**
 * Applies the current rules to mail that was classified by older ones.
 *
 * Classification happens once, when a message is saved, because that is the only point at which the full
 * headers are in hand. That makes every improvement to the rules invisible on the mailbox the user already
 * has: the fix ships, and the mail that was wrong stays wrong until it is deleted and fetched again. For an
 * account synchronised once and read for months, that is nearly all of it.
 *
 * So the store keeps the classification-relevant headers, and this re-derives the verdict from them. It runs
 * only when the rules have actually changed - see [ReclassificationTracker] - so the usual cost is nothing.
 */
class MessageReclassifier(
    private val accountManager: LegacyAccountDtoManager,
    private val messageStoreManager: MessageStoreManager,
    private val messageClassifier: MessageClassifier,
    private val knownContacts: KnownContacts,
    private val knownCorrespondents: KnownCorrespondents,
    private val tracker: ReclassificationTracker,
) {

    /**
     * Re-classifies stored mail if the rules have changed since the last pass.
     *
     * @return how many messages were re-classified.
     */
    fun reclassifyIfRulesChanged(): Int {
        if (!tracker.isPassNeeded(CLASSIFIER_VERSION)) return 0

        val updated = accountManager.getAccounts().sumOf { account ->
            runCatching { reclassify(messageStoreManager.getMessageStore(account)) }.getOrElse { error ->
                // Recorded as done regardless below: an account whose database cannot be read now will not
                // become readable on the next launch either, and retrying the whole pass every time the app
                // starts would be a permanent cost for a permanent failure.
                Log.e(error, "Could not re-classify stored messages for account %s", account.uuid)
                0
            }
        }

        tracker.recordPass(CLASSIFIER_VERSION)
        Log.i("Re-classified %d stored messages against classifier version %d", updated, CLASSIFIER_VERSION)

        return updated
    }

    /**
     * Re-classifies every stored message, whatever version decided it.
     *
     * For when the reader has taught the app something and wants it applied to the mail already in front of
     * them, rather than waiting for the rules themselves to change. Every message is already at the current
     * version, so this cannot page by version the way the automatic pass does - each row it wrote would match
     * again on the next batch - and walks the mailbox by message id instead.
     *
     * @return how many messages were re-classified.
     */
    fun reclassifyEverything(): Int {
        val updated = accountManager.getAccounts().sumOf { account ->
            runCatching { reclassifyAll(messageStoreManager.getMessageStore(account)) }.getOrElse { error ->
                Log.e(error, "Could not re-classify stored messages for account %s", account.uuid)
                0
            }
        }

        tracker.recordPass(CLASSIFIER_VERSION)
        Log.i("Re-classified %d stored messages on request", updated)

        return updated
    }

    private fun reclassifyAll(messageStore: MessageStore): Int {
        var total = 0
        var afterMessageId = 0L

        while (true) {
            val batch = messageStore.getMessagesToReclassify(EVERY_VERSION, BATCH_SIZE, afterMessageId)
            if (batch.isEmpty()) break

            total += messageStore.setClassifications(verdictsFor(batch), CLASSIFIER_VERSION)
            afterMessageId = batch.last().messageId
        }

        return total
    }

    private fun reclassify(messageStore: MessageStore): Int {
        var total = 0
        var updated: Int

        // Continues while a pass makes progress. A batch that wrote nothing would be handed back unchanged
        // next time round, so this is also what stops an unexpected state - rows deleted mid-pass, a write
        // refused - from looping forever on a thread nothing is watching.
        do {
            val batch = messageStore.getMessagesToReclassify(CLASSIFIER_VERSION, BATCH_SIZE)
            updated = if (batch.isEmpty()) {
                0
            } else {
                messageStore.setClassifications(verdictsFor(batch), CLASSIFIER_VERSION)
            }

            total += updated
        } while (updated > 0)

        return total
    }

    private fun verdictsFor(batch: List<StoredClassificationEvidence>): Map<Long, MessageClassification> {
        return batch.associate { stored ->
            val address = stored.evidence.fromAddress
            val evidence = stored.evidence.copy(
                isKnownContact = address?.let { knownContacts.isKnown(it) } == true,
                hasCorresponded = address?.let { knownCorrespondents.isKnown(it) } == true,
            )

            stored.messageId to messageClassifier.classify(evidence)
        }
    }
}
