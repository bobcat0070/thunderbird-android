package net.thunderbird.feature.mail.message.classification.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.SenderClassificationRule

class OverridingMessageClassifierTest {

    @Test
    fun `should fall back to the header rules when nothing was taught`() {
        val testSubject = classifierWith()

        val result = testSubject.classify(evidenceOf("list-unsubscribe" to "<https://example.com/u/1>"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
        assertThat(result.signal).isEqualTo(ClassificationSignal.BULK_HEADER)
    }

    @Test
    fun `a taught sender should beat the header rules`() {
        // The case that makes the feature worth having: a person whose mail carries an unsubscribe link
        // because their employer's mail gateway adds one.
        val testSubject = classifierWith(rule(RuleScope.SENDER, "sam@example.com", MessageClass.HUMAN))

        val result = testSubject.classify(
            evidenceOf("list-unsubscribe" to "<https://example.com/u/1>", from = "sam@example.com"),
        )

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
        assertThat(result.signal).isEqualTo(ClassificationSignal.USER_OVERRIDE)
    }

    @Test
    fun `sender matching should ignore case`() {
        val testSubject = classifierWith(rule(RuleScope.SENDER, "sam@example.com", MessageClass.HUMAN))

        val result = testSubject.classify(evidenceOf(from = "Sam@Example.COM"))

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
    }

    @Test
    fun `a domain rule should match any address at that domain`() {
        // Bulk senders rotate the local part, so a sender-scoped rule would never match twice.
        val testSubject = classifierWith(rule(RuleScope.DOMAIN, "mail.shop.com", MessageClass.NEWSLETTER))

        val result = testSubject.classify(evidenceOf(from = "bounce-8f21@mail.shop.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
        assertThat(result.signal).isEqualTo(ClassificationSignal.USER_OVERRIDE)
    }

    @Test
    fun `a domain rule should not match a different domain`() {
        val testSubject = classifierWith(rule(RuleScope.DOMAIN, "shop.com", MessageClass.NEWSLETTER))

        val result = testSubject.classify(evidenceOf(from = "sam@notshop.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    @Test
    fun `a sender rule should win over a domain rule`() {
        // How a user excepts one person from a domain they have muted.
        val testSubject = classifierWith(
            rule(RuleScope.DOMAIN, "example.com", MessageClass.NEWSLETTER),
            rule(RuleScope.SENDER, "sam@example.com", MessageClass.HUMAN),
        )

        val result = testSubject.classify(evidenceOf(from = "sam@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
    }

    @Test
    fun `teaching the same sender twice should replace the earlier rule`() {
        val store = InMemoryClassificationOverrideStore()
        val testSubject = OverridingMessageClassifier(store, RuleBasedMessageClassifier())
        store.put(rule(RuleScope.SENDER, "sam@example.com", MessageClass.NEWSLETTER))
        store.put(rule(RuleScope.SENDER, "sam@example.com", MessageClass.HUMAN))

        val result = testSubject.classify(evidenceOf(from = "sam@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
    }

    @Test
    fun `a message with no sender should fall through to the header rules`() {
        val testSubject = classifierWith(rule(RuleScope.SENDER, "sam@example.com", MessageClass.HUMAN))

        val result = testSubject.classify(evidenceOf("auto-submitted" to "auto-generated"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
    }

    private fun classifierWith(vararg rules: SenderClassificationRule) = OverridingMessageClassifier(
        overrideStore = InMemoryClassificationOverrideStore(rules.toList()),
        delegate = RuleBasedMessageClassifier(),
    )

    private fun rule(scope: RuleScope, pattern: String, messageClass: MessageClass) =
        SenderClassificationRule(scope, pattern, messageClass, createdAt = ruleTime++)

    private var ruleTime = 1L

    private fun evidenceOf(vararg headers: Pair<String, String>, from: String? = null) = MessageEvidence(
        headers = headers.groupBy({ it.first }, { it.second }),
        fromAddress = from,
        recipientCount = 1,
    )
}
