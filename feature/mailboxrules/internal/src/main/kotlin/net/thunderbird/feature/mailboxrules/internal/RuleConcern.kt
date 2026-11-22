package net.thunderbird.feature.mailboxrules.internal

import net.thunderbird.feature.mailboxrules.MailboxRule

/**
 * What about a rule is worth a look: what someone who got into the mailbox sets up to keep reading it, or to keep
 * the reader from seeing a warning.
 */
internal sealed interface RuleConcern {
    /** Mail is forwarded or redirected outside the mailbox's own domain. */
    data class ForwardsOutside(val addresses: List<String>) : RuleConcern

    /** Mail is deleted as it arrives. */
    data object Deletes : RuleConcern

    /** Mail is moved away and marked read, so it never shows as new. */
    data object Hides : RuleConcern
}

/**
 * @param mailboxAddress the mailbox's own address, whose domain counts as inside.
 */
internal fun MailboxRule.concerns(mailboxAddress: String): List<RuleConcern> {
    val ownDomain = domainOf(mailboxAddress)
    val outside = forwardsTo.map { it.trim() }.filter { address ->
        val domain = domainOf(address)
        domain == null || ownDomain == null || !(domain == ownDomain || domain.endsWith(".$ownDomain"))
    }

    return buildList {
        if (outside.isNotEmpty()) add(RuleConcern.ForwardsOutside(outside))
        if (deletes) add(RuleConcern.Deletes)
        if (movesAndMarksRead) add(RuleConcern.Hides)
    }
}

/**
 * The rules with something worth a look first, those switched on before those off, then the rest in their order.
 */
internal fun List<MailboxRule>.sortedForReview(mailboxAddress: String): List<MailboxRule> =
    sortedWith(compareBy<MailboxRule> { it.concerns(mailboxAddress).isEmpty() }.thenBy { !it.isEnabled })

private fun domainOf(address: String): String? =
    address.substringAfterLast('@', missingDelimiterValue = "").trim().lowercase().takeIf { it.isNotEmpty() }
