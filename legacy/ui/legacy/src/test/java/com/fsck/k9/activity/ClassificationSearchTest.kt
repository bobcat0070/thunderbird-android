package com.fsck.k9.activity

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import org.junit.Test

class ClassificationSearchTest {

    @Test
    fun `should search for the chosen class`() {
        val search = LocalMessageSearch().apply { addAllowedFolder(folderId = 9) }

        val narrowed = search.narrowedToClassification(MessageClass.NEWSLETTER)

        assertThat(narrowed.leafConditions()).contains(
            SearchCondition(MessageSearchField.CLASSIFICATION, SearchAttribute.EQUALS, "NEWSLETTER"),
        )
    }

    @Test
    fun `should keep the folders the original search was limited to`() {
        val search = LocalMessageSearch().apply { addAllowedFolder(folderId = 9) }

        val narrowed = search.narrowedToClassification(MessageClass.NOTIFICATION)

        assertThat(narrowed.folderIds).isEqualTo(listOf(9L))
    }

    @Test
    fun `should keep a condition that names no folder at all`() {
        // What the unified inbox is: an "integrate" condition and nothing else. Carrying only folder ids left
        // this search matching every folder of every account, so a message deleted from a category list moved
        // to the trash and still matched - the row stayed, marked read, looking as though it had not been
        // deleted at all.
        val search = LocalMessageSearch().apply {
            and(MessageSearchField.INTEGRATE, "1", SearchAttribute.EQUALS)
        }

        val narrowed = search.narrowedToClassification(MessageClass.NOTIFICATION)

        assertThat(narrowed.leafConditions()).contains(
            SearchCondition(MessageSearchField.INTEGRATE, SearchAttribute.EQUALS, "1"),
        )
    }

    @Test
    fun `should keep every condition of a search built from several`() {
        val search = LocalMessageSearch().apply {
            and(MessageSearchField.INTEGRATE, "1", SearchAttribute.EQUALS)
            and(MessageSearchField.READ, "0", SearchAttribute.EQUALS)
        }

        val narrowed = search.narrowedToClassification(MessageClass.HUMAN)

        assertThat(narrowed.leafConditions()).contains(
            SearchCondition(MessageSearchField.INTEGRATE, SearchAttribute.EQUALS, "1"),
        )
        assertThat(narrowed.leafConditions()).contains(
            SearchCondition(MessageSearchField.READ, SearchAttribute.EQUALS, "0"),
        )
    }

    @Test
    fun `should keep the accounts the original search was limited to`() {
        val search = LocalMessageSearch().apply {
            addAccountUuid("account-one")
            addAccountUuid("account-two")
        }

        val narrowed = search.narrowedToClassification(MessageClass.NEWSLETTER)

        assertThat(narrowed.accountUuids).isEqualTo(setOf("account-one", "account-two"))
    }

    @Test
    fun `should keep searching every account when the original did`() {
        // An empty set means "all accounts", so it has to stay empty rather than become a list of none.
        val search = LocalMessageSearch().apply {
            and(MessageSearchField.INTEGRATE, "1", SearchAttribute.EQUALS)
        }

        val narrowed = search.narrowedToClassification(MessageClass.NEWSLETTER)

        assertThat(narrowed.searchAllAccounts()).isEqualTo(true)
    }

    @Test
    fun `should keep the id of the list it was opened from`() {
        // The id is what the rest of the app uses to tell which list is on screen.
        val search = LocalMessageSearch().apply { id = "unified_inbox" }

        val narrowed = search.narrowedToClassification(MessageClass.NOTIFICATION)

        assertThat(narrowed.id).isEqualTo("unified_inbox")
    }

    private fun LocalMessageSearch.leafConditions(): List<SearchCondition> = leafSet.mapNotNull { it.condition }
}
