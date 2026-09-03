package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageStoreManager
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.legacy.logging.Log
import net.thunderbird.feature.mail.message.classification.api.CLASSIFIER_VERSION
import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.SenderClassificationRule
import net.thunderbird.feature.mail.message.classification.api.senderDomainOrNull

/**
 * Records the user's corrections and applies them to mail that has already arrived.
 *
 * Teaching has to do both. A rule that only affected future mail would look broken: the user corrects a
 * message, the list does not change, and there is nothing to tell them the correction was even recorded.
 */
class MessageClassificationTeacher(
    private val overrideStore: ClassificationOverrideStore,
    private val accountManager: LegacyAccountDtoManager,
    private val messageStoreManager: MessageStoreManager,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Records that mail from [fromAddress] is [messageClass], and re-classifies what is already stored.
     *
     * @param scope [RuleScope.SENDER] to correct just this address, [RuleScope.DOMAIN] for everything at its
     *   domain — the escape hatch for bulk senders that rotate the local part of their address.
     * @return how many stored messages changed, across all accounts.
     */
    fun teach(fromAddress: String, messageClass: MessageClass, scope: RuleScope = RuleScope.SENDER): Int {
        val pattern = patternFor(fromAddress, scope) ?: return 0

        overrideStore.put(
            SenderClassificationRule(
                scope = scope,
                pattern = pattern,
                messageClass = messageClass,
                createdAt = currentTimeMillis(),
            ),
        )

        val updated = applyToStoredMessages(scope, pattern, messageClass)
        Log.d("Taught %s = %s, re-classified %d stored messages", pattern, messageClass, updated)

        return updated
    }

    /**
     * Drops a rule the user no longer wants.
     *
     * Stored messages keep the class the rule gave them: re-deriving what they would have been means
     * re-reading headers this app no longer keeps for old mail, and silently moving a pile of messages back
     * is a worse surprise than leaving them where the user last saw them. New mail classifies normally.
     */
    fun forget(fromAddress: String, scope: RuleScope = RuleScope.SENDER) {
        val pattern = patternFor(fromAddress, scope) ?: return

        overrideStore.remove(scope, pattern)
    }

    private fun patternFor(fromAddress: String, scope: RuleScope): String? {
        val address = fromAddress.trim().lowercase()
        if (address.isEmpty()) return null

        return when (scope) {
            RuleScope.SENDER -> address
            RuleScope.DOMAIN -> address.senderDomainOrNull()
        }
    }

    /**
     * Applies to every account, because the rule is about the sender rather than about one mailbox: the same
     * sender reaching a work and a personal account is the same sender.
     */
    private fun applyToStoredMessages(scope: RuleScope, pattern: String, messageClass: MessageClass): Int {
        return accountManager.getAccounts().sumOf { account ->
            runCatching {
                messageStoreManager.getMessageStore(account).setClassificationForSender(
                    scope = scope,
                    pattern = pattern,
                    messageClass = messageClass,
                    signal = ClassificationSignal.USER_OVERRIDE.name,
                    classifierVersion = CLASSIFIER_VERSION,
                )
            }.getOrElse { error ->
                // One unreadable account database must not lose the correction for the others; the rule is
                // already stored, so new mail is classified correctly regardless.
                Log.e(error, "Could not re-classify stored messages for account %s", account.uuid)
                0
            }
        }
    }
}
