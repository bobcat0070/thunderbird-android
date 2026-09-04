package net.thunderbird.feature.mail.message.classification.api

/**
 * How widely a taught correction applies.
 */
enum class RuleScope {
    /**
     * One exact address.
     *
     * The safe default: correcting one message should not silently retag a whole domain the user never
     * looked at.
     */
    SENDER,

    /**
     * Every address at a domain.
     *
     * Needed because bulk senders rotate the local part (`bounce-8f21@mail.example.com`), so a
     * sender-scoped rule taught from one message would never match the next one.
     */
    DOMAIN,
}

/**
 * A correction the user taught.
 *
 * @param scope whether [pattern] is an address or a domain.
 * @param pattern lower-cased address or bare domain, without the `@`.
 * @param messageClass what mail matching this rule should be classified as.
 * @param createdAt when the user taught it, epoch milliseconds.
 */
data class SenderClassificationRule(
    val scope: RuleScope,
    val pattern: String,
    val messageClass: MessageClass,
    val createdAt: Long,
)

/**
 * Where taught corrections live.
 *
 * Deliberately global rather than per-account: "mail from this sender is a newsletter" is a fact about the
 * sender, and a user with a work and a personal account should not have to teach it twice.
 */
interface ClassificationOverrideStore {
    /**
     * @return every rule, most recently taught first.
     */
    fun rules(): List<SenderClassificationRule>

    /**
     * Adds [rule], replacing any existing rule with the same scope and pattern.
     */
    fun put(rule: SenderClassificationRule)

    /**
     * Removes the rule with this scope and pattern, if there is one.
     */
    fun remove(scope: RuleScope, pattern: String)
}

/**
 * @return the address's domain, lower-cased, or `null` when this is not an address with a domain.
 */
fun String.senderDomainOrNull(): String? {
    val domain = substringAfterLast('@', missingDelimiterValue = "").trim().lowercase()

    return domain.ifEmpty { null }
}
