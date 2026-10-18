package net.thunderbird.feature.search.legacy

import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition

/**
 * What the stored address list puts between an address and its display name.
 */
private const val NAME_SEPARATOR = ";\u0001"

/**
 * @return a search for all mail from [address], in every account, except what is already in Trash or Spam.
 *
 * Trash is left out because deleting from it is permanent, and a "delete all" over this list must only ever move
 * mail to the trash. Spam is left out because this is the mail the reader is managing, not the mail they never
 * wanted; the spam folder has its own tools.
 */
fun createSenderSearch(address: String): LocalMessageSearch {
    val normalized = address.trim().lowercase()

    return LocalMessageSearch().apply {
        isManualSearch = true
        and(SearchCondition(MessageSearchField.SENDER_ADDRESS, SearchAttribute.CONTAINS, normalized + NAME_SEPARATOR))
        and(
            SearchConditionTreeNode.Builder(specialFolder(UnifiedFolderKind.TRASH))
                .or(specialFolder(UnifiedFolderKind.SPAM))
                .not()
                .build(),
        )
    }
}

/**
 * The address a search made by [createSenderSearch] is for, or `null` for any other search.
 *
 * Read from the search's own condition, which travels with it wherever the search is passed on.
 */
val LocalMessageSearch.senderSearchAddress: String?
    get() = conditions.getLeafSet().firstNotNullOfOrNull { node ->
        node.condition
            ?.takeIf { condition ->
                condition.field == MessageSearchField.SENDER_ADDRESS &&
                    condition.attribute == SearchAttribute.CONTAINS &&
                    condition.value.endsWith(NAME_SEPARATOR)
            }
            ?.value
            ?.removeSuffix(NAME_SEPARATOR)
    }

private fun specialFolder(kind: UnifiedFolderKind) =
    SearchCondition(MessageSearchField.SPECIAL_FOLDER, SearchAttribute.EQUALS, kind.name)
