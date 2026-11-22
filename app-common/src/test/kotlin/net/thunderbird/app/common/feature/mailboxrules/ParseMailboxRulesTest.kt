package net.thunderbird.app.common.feature.mailboxrules

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import net.thunderbird.feature.mailboxrules.MailboxRule
import org.junit.Test

class ParseMailboxRulesTest {

    @Test
    fun `rules should be read with where they send mail and whether they hide it`() {
        val json = """
            {"value": [
              {"displayName": "Copies", "isEnabled": true, "actions": {
                "forwardTo": [{"emailAddress": {"name": "Copies", "address": "copies@freemail.example"}}],
                "redirectTo": [{"emailAddress": {"address": "desk@company.example"}}],
                "stopProcessingRules": true}},
              {"displayName": "Quiet", "isEnabled": false, "actions": {
                "moveToFolder": "AAMkAGI2demo", "markAsRead": true}},
              {"displayName": "Gone", "actions": {"permanentDelete": true}}
            ]}
        """.trimIndent()

        val result = parseMailboxRules(json)

        assertThat(result).containsExactly(
            MailboxRule("Copies", true, listOf("copies@freemail.example", "desk@company.example"), false, false),
            MailboxRule("Quiet", false, emptyList(), deletes = false, movesAndMarksRead = true),
            MailboxRule("Gone", true, emptyList(), deletes = true, movesAndMarksRead = false),
        )
    }

    @Test
    fun `an answer without rules should give none`() {
        assertThat(parseMailboxRules("""{"value": []}""")).isEmpty()
        assertThat(parseMailboxRules("""{}""")).isEmpty()
    }
}
