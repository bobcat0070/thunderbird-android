package net.thunderbird.feature.search.legacy

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.feature.search.legacy.sql.SqlWhereClause

private const val TRASH_FOLDER_ID = 7L
private const val SPAM_FOLDER_ID = 9L

class SenderSearchTest {
    @Test
    fun `a sender search should match the first sender's exact address outside trash and spam`() {
        val search = createSenderSearch(" News@Shop.Example ")

        val clause = SqlWhereClause.Builder()
            .withConditions(
                search.conditions.resolveSpecialFolders { kind ->
                    when (kind) {
                        UnifiedFolderKind.TRASH -> TRASH_FOLDER_ID
                        UnifiedFolderKind.SPAM -> SPAM_FOLDER_ID
                        else -> null
                    }
                },
            )
            .build()

        assertThat(clause.selection).isEqualTo(
            "(instr(lower(messages.sender_list) || ';' || char(1), ?) = 1) AND " +
                "(NOT ((folder_id = ?) OR (folder_id = ?)))",
        )
        assertThat(clause.selectionArgs).containsExactly("news@shop.example;\u0001", "7", "9")
    }

    @Test
    fun `a sender search should cover every account and say which sender it is for`() {
        val search = createSenderSearch("News@Shop.Example")

        assertThat(search.searchAllAccounts()).isTrue()
        assertThat(search.senderSearchAddress).isEqualTo("news@shop.example")
        assertThat(createUnifiedFolderSearch(UnifiedFolderKind.SENT).senderSearchAddress).isNull()
    }
}
