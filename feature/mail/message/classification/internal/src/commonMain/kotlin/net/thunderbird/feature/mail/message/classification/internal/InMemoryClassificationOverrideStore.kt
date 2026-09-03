package net.thunderbird.feature.mail.message.classification.internal

import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.SenderClassificationRule

/**
 * A store with no persistence, for tests and for platforms that have not wired one up yet.
 */
class InMemoryClassificationOverrideStore(
    initialRules: List<SenderClassificationRule> = emptyList(),
) : ClassificationOverrideStore {

    private val storedRules = initialRules.toMutableList()

    override fun rules(): List<SenderClassificationRule> = storedRules.sortedByDescending { it.createdAt }

    override fun put(rule: SenderClassificationRule) {
        storedRules.removeAll { it.scope == rule.scope && it.pattern == rule.pattern }
        storedRules.add(rule)
    }

    override fun remove(scope: RuleScope, pattern: String) {
        storedRules.removeAll { it.scope == scope && it.pattern == pattern }
    }
}
