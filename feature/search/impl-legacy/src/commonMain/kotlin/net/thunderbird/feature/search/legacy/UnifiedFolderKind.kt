package net.thunderbird.feature.search.legacy

import net.thunderbird.feature.search.legacy.SearchConditionTreeNode.Operator
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition

/**
 * A folder shown once for every account together: all inboxes as one list, all sent mail as another.
 *
 * Ordered as they are listed, which is the order a mail client conventionally presents them in.
 *
 * @param searchId identifies the search, so a list can tell which unified folder it is showing after being
 *   recreated. The inbox keeps [SearchAccount.UNIFIED_FOLDERS], the id it has always had, because notifications,
 *   widgets and saved intents already refer to it.
 */
enum class UnifiedFolderKind(val searchId: String) {
    INBOX(SearchAccount.UNIFIED_FOLDERS),
    DRAFTS("unified_drafts"),
    SENT("unified_sent"),
    ARCHIVE("unified_archive"),
    SPAM("unified_spam"),
    TRASH("unified_trash"),
    ;

    companion object {
        /**
         * @return the unified folder a search shows, or `null` for any other search.
         */
        fun fromSearchId(searchId: String?): UnifiedFolderKind? = entries.firstOrNull { it.searchId == searchId }
    }
}

/**
 * @return a search showing [kind] across every account.
 *
 * The inbox is selected by the folders' `integrate` flag, which the user controls per folder. Every other kind is
 * selected by role, because which folder is an account's Sent folder is an account setting - chosen by the user or
 * by name on servers that do not mark their special folders - and not something a folder records about itself.
 */
fun createUnifiedFolderSearch(kind: UnifiedFolderKind): LocalMessageSearch {
    return LocalMessageSearch().apply {
        id = kind.searchId
        if (kind == UnifiedFolderKind.INBOX) {
            and(MessageSearchField.INTEGRATE, "1", SearchAttribute.EQUALS)
        } else {
            and(MessageSearchField.SPECIAL_FOLDER, kind.name, SearchAttribute.EQUALS)
        }
    }
}

/**
 * The id no folder has, for an account without a folder of the kind asked for. Its mail is left out rather than
 * the condition being dropped, which would show every folder of that account instead.
 */
private const val NO_FOLDER_ID = "-1"

/**
 * @return this tree with every special-folder condition replaced by the folder [folderIdFor] names for one
 *   account, or by a condition nothing matches when that account has no such folder.
 *
 * Must be applied separately for each account a search runs against, because the same role is a different folder
 * in every account's database. A tree with no special-folder condition in it is returned as it is.
 */
fun SearchConditionTreeNode.resolveSpecialFolders(
    folderIdFor: (UnifiedFolderKind) -> Long?,
): SearchConditionTreeNode {
    if (getLeafSet().none { it.condition?.field == MessageSearchField.SPECIAL_FOLDER }) return this

    return resolve(folderIdFor)
}

private fun SearchConditionTreeNode.resolve(folderIdFor: (UnifiedFolderKind) -> Long?): SearchConditionTreeNode {
    return when (operator) {
        Operator.CONDITION -> resolveLeaf(folderIdFor)

        Operator.NOT -> SearchConditionTreeNode.Builder(requireNotNull(left).resolve(folderIdFor)).not().build()

        Operator.AND -> SearchConditionTreeNode.Builder(requireNotNull(left).resolve(folderIdFor))
            .and(requireNotNull(right).resolve(folderIdFor))
            .build()

        Operator.OR -> SearchConditionTreeNode.Builder(requireNotNull(left).resolve(folderIdFor))
            .or(requireNotNull(right).resolve(folderIdFor))
            .build()
    }
}

private fun SearchConditionTreeNode.resolveLeaf(folderIdFor: (UnifiedFolderKind) -> Long?): SearchConditionTreeNode {
    val condition = requireNotNull(condition)
    if (condition.field != MessageSearchField.SPECIAL_FOLDER) return this

    val kind = UnifiedFolderKind.entries.firstOrNull { it.name == condition.value }
    val folderId = kind?.let(folderIdFor)?.toString() ?: NO_FOLDER_ID

    return SearchConditionTreeNode.Builder(
        SearchCondition(MessageSearchField.FOLDER, SearchAttribute.EQUALS, folderId),
    ).build()
}
