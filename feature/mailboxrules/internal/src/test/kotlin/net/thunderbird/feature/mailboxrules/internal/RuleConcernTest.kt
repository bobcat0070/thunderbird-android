package net.thunderbird.feature.mailboxrules.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.feature.mailboxrules.MailboxRule

private const val MAILBOX = "alex@company.example"

class RuleConcernTest {

    @Test
    fun `forwarding outside the mailbox's domain should be a concern`() {
        val testSubject = rule(forwardsTo = listOf("team@company.example", "Copies@Freemail.example"))

        assertThat(testSubject.concerns(MAILBOX)).containsExactly(
            RuleConcern.ForwardsOutside(listOf("Copies@Freemail.example")),
        )
    }

    @Test
    fun `forwarding inside the domain or a subdomain should not be a concern`() {
        val testSubject = rule(forwardsTo = listOf("team@company.example", "desk@eu.company.example"))

        assertThat(testSubject.concerns(MAILBOX)).isEmpty()
    }

    @Test
    fun `deleting and hiding should be concerns`() {
        val testSubject = rule(deletes = true, movesAndMarksRead = true)

        assertThat(testSubject.concerns(MAILBOX)).containsExactly(RuleConcern.Deletes, RuleConcern.Hides)
    }

    @Test
    fun `rules worth a look should come first, those switched on before those off`() {
        val tidy = rule(name = "tidy")
        val offForward = rule(name = "off", isEnabled = false, forwardsTo = listOf("x@other.example"))
        val onDelete = rule(name = "on", deletes = true)

        val result = listOf(tidy, offForward, onDelete).sortedForReview(MAILBOX)

        assertThat(result.map { it.name }).containsExactly("on", "off", "tidy")
    }

    private fun rule(
        name: String = "Rule",
        isEnabled: Boolean = true,
        forwardsTo: List<String> = emptyList(),
        deletes: Boolean = false,
        movesAndMarksRead: Boolean = false,
    ) = MailboxRule(name, isEnabled, forwardsTo, deletes, movesAndMarksRead)
}
