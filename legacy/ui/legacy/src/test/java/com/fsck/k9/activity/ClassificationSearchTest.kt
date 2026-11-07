package com.fsck.k9.activity

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.folder.FolderRole
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.feature.search.legacy.createUnifiedFolderSearch
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
    fun `should stay within the folders of the unified list it was opened from`() {
        // The unified inbox names no folder: each account's inbox is resolved when the search runs. Carrying only
        // folder ids left this search matching every folder of every account, so a message deleted from a
        // category list moved to the trash and still matched - the row stayed, marked read, looking as though it
        // had not been deleted at all.
        val search = LocalMessageSearch.createUnifiedSearch(FolderRole.INBOX)

        val narrowed = search.narrowedToClassification(MessageClass.NOTIFICATION)

        val conditions = narrowed.conditionsForAccount { role -> if (role == FolderRole.INBOX) INBOX_ID else null }
        assertThat(conditions?.getLeafSet().orEmpty().mapNotNull { it.condition }).contains(
            SearchCondition(MessageSearchField.FOLDER, SearchAttribute.EQUALS, INBOX_ID.toString()),
        )
    }

    @Test
    fun `should keep every condition of a search built from several`() {
        val search = LocalMessageSearch().apply {
            and(MessageSearchField.FLAGGED, "1", SearchAttribute.EQUALS)
            and(MessageSearchField.READ, "0", SearchAttribute.EQUALS)
        }

        val narrowed = search.narrowedToClassification(MessageClass.HUMAN)

        assertThat(narrowed.leafConditions()).contains(
            SearchCondition(MessageSearchField.FLAGGED, SearchAttribute.EQUALS, "1"),
        )
        assertThat(narrowed.leafConditions()).contains(
            SearchCondition(MessageSearchField.READ, SearchAttribute.EQUALS, "0"),
        )
    }

    @Test
    fun `should keep the accounts the original search was limited to`() {
        val accountOne = AccountIdFactory.create()
        val accountTwo = AccountIdFactory.create()
        val search = LocalMessageSearch().apply {
            addAccountId(accountOne)
            addAccountId(accountTwo)
        }

        val narrowed = search.narrowedToClassification(MessageClass.NEWSLETTER)

        assertThat(narrowed.accountIds).isEqualTo(setOf(accountOne, accountTwo))
    }

    @Test
    fun `should keep searching every account when the original did`() {
        // An empty set means "all accounts", so it has to stay empty rather than become a list of none.
        val search = LocalMessageSearch().apply {
            and(MessageSearchField.FLAGGED, "1", SearchAttribute.EQUALS)
        }

        val narrowed = search.narrowedToClassification(MessageClass.NEWSLETTER)

        assertThat(narrowed.searchAllAccounts()).isEqualTo(true)
    }

    @Test
    fun `should keep the id and type of the list it was opened from`() {
        // They are what the rest of the app uses to tell which list is on screen.
        val search = createUnifiedFolderSearch(UnifiedFolderKind.SENT)

        val narrowed = search.narrowedToClassification(MessageClass.NOTIFICATION)

        assertThat(narrowed.id).isEqualTo(search.id)
        assertThat(narrowed.type).isEqualTo(search.type)
    }

    private fun LocalMessageSearch.leafConditions(): List<SearchCondition> = leafSet.mapNotNull { it.condition }
}

private const val INBOX_ID = 7L
