package net.thunderbird.feature.mail.message.classification.internal

import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClassification
import net.thunderbird.feature.mail.message.classification.api.MessageClassifier
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.senderDomainOrNull

/**
 * Applies the user's taught corrections before falling back to [delegate].
 *
 * Separate from [RuleBasedMessageClassifier] rather than folded into it so the header rules stay a pure
 * function that can be tested against captured mail, and so the part that has to touch storage is the part
 * with no logic in it.
 */
class OverridingMessageClassifier(
    private val overrideStore: ClassificationOverrideStore,
    private val delegate: MessageClassifier,
) : MessageClassifier {

    override fun classify(evidence: MessageEvidence): MessageClassification {
        val override = evidence.fromAddress?.let { overrideFor(it) }
        if (override != null) return override

        return delegate.classify(evidence)
    }

    /**
     * A sender rule beats a domain rule: it is the more specific thing the user said, and the usual reason
     * to have both is to except one address from a domain-wide correction.
     */
    private fun overrideFor(fromAddress: String): MessageClassification? {
        val address = fromAddress.trim().lowercase()
        if (address.isEmpty()) return null

        val rules = overrideStore.rules()
        val match = rules.firstOrNull { it.scope == RuleScope.SENDER && it.pattern == address }
            ?: address.senderDomainOrNull()?.let { domain ->
                rules.firstOrNull { it.scope == RuleScope.DOMAIN && it.pattern == domain }
            }
            ?: return null

        return MessageClassification(match.messageClass, ClassificationSignal.USER_OVERRIDE)
    }
}
