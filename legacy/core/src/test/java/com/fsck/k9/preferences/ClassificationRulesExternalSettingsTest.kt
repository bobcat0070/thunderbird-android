package com.fsck.k9.preferences

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import com.fsck.k9.preferences.ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.SenderClassificationRule
import org.junit.Test

class ClassificationRulesExternalSettingsTest : RobolectricTest() {

    @Test
    fun `rules should survive a round trip`() {
        val source = FakeOverrideStore(listOf(NEWSLETTER_RULE, DOMAIN_RULE))
        val target = FakeOverrideStore()

        ClassificationRulesExternalSettings(target).importSettings(
            ClassificationRulesExternalSettings(source).exportSettings(),
        )

        assertThat(target.rules()).containsExactlyInAnyOrder(NEWSLETTER_RULE, DOMAIN_RULE)
    }

    @Test
    fun `import should add to the rules already here`() {
        val target = FakeOverrideStore(listOf(DOMAIN_RULE))

        ClassificationRulesExternalSettings(target).importSettings(exportOf(NEWSLETTER_RULE))

        assertThat(target.rules()).containsExactlyInAnyOrder(NEWSLETTER_RULE, DOMAIN_RULE)
    }

    @Test
    fun `a newer correction here should win over an older imported one`() {
        // Importing an old export must not undo a correction made since.
        val newerHere = NEWSLETTER_RULE.copy(messageClass = MessageClass.HUMAN, createdAt = 2_000)
        val target = FakeOverrideStore(listOf(newerHere))

        ClassificationRulesExternalSettings(target).importSettings(exportOf(NEWSLETTER_RULE.copy(createdAt = 1_000)))

        assertThat(target.rules()).containsExactlyInAnyOrder(newerHere)
    }

    @Test
    fun `a newer imported correction should replace an older one here`() {
        val olderHere = NEWSLETTER_RULE.copy(messageClass = MessageClass.HUMAN, createdAt = 1_000)
        val newerImported = NEWSLETTER_RULE.copy(createdAt = 2_000)
        val target = FakeOverrideStore(listOf(olderHere))

        ClassificationRulesExternalSettings(target).importSettings(exportOf(newerImported))

        assertThat(target.rules()).containsExactlyInAnyOrder(newerImported)
    }

    @Test
    fun `an unreadable value should be skipped rather than fail the import`() {
        val target = FakeOverrideStore()

        val unreadable = mapOf(LEARNED_CLASSIFICATION_RULES_KEY to "{not json")

        ClassificationRulesExternalSettings(target).importSettings(unreadable)

        assertThat(target.rules()).isEmpty()
    }

    @Test
    fun `a rule for a class this version does not know should be skipped, keeping the rest`() {
        // Written by a newer app; the rules this version understands still import.
        val value = """[{"scope":"SENDER","pattern":"a@example.com","class":"FUTURE_CLASS","createdAt":1},
            {"scope":"SENDER","pattern":"news@example.com","class":"NEWSLETTER","createdAt":1000}]"""
        val target = FakeOverrideStore()

        ClassificationRulesExternalSettings(target).importSettings(mapOf(LEARNED_CLASSIFICATION_RULES_KEY to value))

        assertThat(target.rules()).containsExactlyInAnyOrder(NEWSLETTER_RULE)
    }

    private fun exportOf(vararg rules: SenderClassificationRule): Map<String, String> =
        ClassificationRulesExternalSettings(FakeOverrideStore(rules.toList())).exportSettings()

    private class FakeOverrideStore(initial: List<SenderClassificationRule> = emptyList()) :
        ClassificationOverrideStore {
        private val rules = initial.toMutableList()

        override fun rules(): List<SenderClassificationRule> = rules.toList()

        override fun put(rule: SenderClassificationRule) {
            rules.removeAll { it.scope == rule.scope && it.pattern == rule.pattern }
            rules += rule
        }

        override fun remove(scope: RuleScope, pattern: String) {
            rules.removeAll { it.scope == scope && it.pattern == pattern }
        }
    }

    private companion object {
        val NEWSLETTER_RULE = SenderClassificationRule(
            scope = RuleScope.SENDER,
            pattern = "news@example.com",
            messageClass = MessageClass.NEWSLETTER,
            createdAt = 1_000,
        )
        val DOMAIN_RULE = SenderClassificationRule(
            scope = RuleScope.DOMAIN,
            pattern = "shop.example",
            messageClass = MessageClass.NOTIFICATION,
            createdAt = 500,
        )
    }
}
