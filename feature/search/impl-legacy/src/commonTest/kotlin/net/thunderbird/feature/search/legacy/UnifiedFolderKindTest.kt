package net.thunderbird.feature.search.legacy

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import net.thunderbird.feature.search.legacy.sql.SqlWhereClause

private const val SENT_FOLDER_ID = 42L

class UnifiedFolderKindTest {

    @Test
    fun `the unified inbox should keep the search id everything already refers to`() {
        assertThat(UnifiedFolderKind.INBOX.searchId).isEqualTo(SearchAccount.UNIFIED_FOLDERS)
    }

    @Test
    fun `a search id should map back to its unified folder`() {
        assertThat(UnifiedFolderKind.fromSearchId("unified_sent")).isEqualTo(UnifiedFolderKind.SENT)
        assertThat(UnifiedFolderKind.fromSearchId(SearchAccount.UNIFIED_FOLDERS)).isEqualTo(UnifiedFolderKind.INBOX)
        assertThat(UnifiedFolderKind.fromSearchId("something_else")).isNull()
        assertThat(UnifiedFolderKind.fromSearchId(null)).isNull()
    }

    @Test
    fun `the unified inbox should still be chosen by the folders integrate flag`() {
        val search = createUnifiedFolderSearch(UnifiedFolderKind.INBOX)

        assertThat(search.id).isEqualTo(SearchAccount.UNIFIED_FOLDERS)
        assertThat(search.conditions.condition)
            .isEqualTo(SearchCondition(MessageSearchField.INTEGRATE, SearchAttribute.EQUALS, "1"))
    }

    @Test
    fun `a unified special folder should be chosen by role`() {
        val search = createUnifiedFolderSearch(UnifiedFolderKind.SENT)

        assertThat(search.id).isEqualTo("unified_sent")
        assertThat(search.conditions.condition)
            .isEqualTo(SearchCondition(MessageSearchField.SPECIAL_FOLDER, SearchAttribute.EQUALS, "SENT"))
        // Across every account, not limited to one.
        assertThat(search.searchAllAccounts()).isEqualTo(true)
    }

    @Test
    fun `a role should resolve to the folder the account uses for it`() {
        val search = createUnifiedFolderSearch(UnifiedFolderKind.SENT)

        val resolved = search.conditions.resolveSpecialFolders { kind ->
            if (kind == UnifiedFolderKind.SENT) SENT_FOLDER_ID else null
        }

        assertThat(resolved.condition)
            .isEqualTo(SearchCondition(MessageSearchField.FOLDER, SearchAttribute.EQUALS, "42"))
    }

    @Test
    fun `an account without the folder should contribute no mail rather than all of it`() {
        // Dropping the condition instead would list every folder of that account in the unified Sent folder.
        val search = createUnifiedFolderSearch(UnifiedFolderKind.ARCHIVE)

        val resolved = search.conditions.resolveSpecialFolders { null }

        assertThat(resolved.condition)
            .isEqualTo(SearchCondition(MessageSearchField.FOLDER, SearchAttribute.EQUALS, "-1"))
    }

    @Test
    fun `a role inside a larger expression should resolve and keep the rest`() {
        // What a search typed while the unified Sent folder is open looks like.
        val tree = SearchConditionTreeNode.Builder(
            SearchCondition(MessageSearchField.SPECIAL_FOLDER, SearchAttribute.EQUALS, "SENT"),
        ).and(
            SearchConditionTreeNode.Builder(
                SearchCondition(MessageSearchField.SUBJECT, SearchAttribute.CONTAINS, "invoice"),
            ).not().build(),
        ).build()

        val resolved = tree.resolveSpecialFolders { SENT_FOLDER_ID }

        assertThat(resolved.getLeafSet().mapNotNull { it.condition }.sortedBy { it.field.fieldName })
            .containsExactly(
                SearchCondition(MessageSearchField.FOLDER, SearchAttribute.EQUALS, "42"),
                SearchCondition(MessageSearchField.SUBJECT, SearchAttribute.CONTAINS, "invoice"),
            )
        assertThat(resolved.operator).isEqualTo(SearchConditionTreeNode.Operator.AND)
        assertThat(resolved.right?.operator).isEqualTo(SearchConditionTreeNode.Operator.NOT)
    }

    @Test
    fun `a search without a role should be returned untouched`() {
        val tree = SearchConditionTreeNode.Builder(
            SearchCondition(MessageSearchField.INTEGRATE, SearchAttribute.EQUALS, "1"),
        ).build()

        assertThat(tree.resolveSpecialFolders { SENT_FOLDER_ID }).isSameInstanceAs(tree)
    }

    @Test
    fun `an unresolved role should match nothing rather than reach the database`() {
        // A consumer that forgets to resolve gets an empty list, not a SQL error on a column that does not exist.
        val search = createUnifiedFolderSearch(UnifiedFolderKind.SENT)

        val clause = SqlWhereClause.Builder().withConditions(search.conditions).build()

        assertThat(clause.selection).isEqualTo("0")
        assertThat(clause.selectionArgs).containsExactly()
    }

    @Test
    fun `a resolved role should become an ordinary folder condition`() {
        val search = createUnifiedFolderSearch(UnifiedFolderKind.SENT)

        val clause = SqlWhereClause.Builder()
            .withConditions(search.conditions.resolveSpecialFolders { SENT_FOLDER_ID })
            .build()

        assertThat(clause.selection).isEqualTo("folder_id = ?")
        assertThat(clause.selectionArgs).containsExactly("42")
    }
}
