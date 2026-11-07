package net.thunderbird.feature.search.legacy

import net.thunderbird.feature.mail.folder.FolderRole
import net.thunderbird.feature.search.legacy.SearchConditionTreeNode.Operator
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition

/**
 * A folder shown once for every account together: all inboxes as one list, all sent mail as another.
 *
 * Ordered as they are listed, which is the order a mail client conventionally presents them in.
 *
 * @param searchId identifies the unified folder in views that need a string, such as the drawer's folder list and
 *   per-view settings. The inbox keeps "unified_folders", the id it has always had there. A search itself carries
 *   its unified folder in [LocalMessageSearch.type]; see [fromSearch].
 * @param role the special folder this shows from each account.
 */
enum class UnifiedFolderKind(val searchId: String, val role: FolderRole) {
    INBOX("unified_folders", FolderRole.INBOX),
    DRAFTS("unified_drafts", FolderRole.DRAFTS),
    SENT("unified_sent", FolderRole.SENT),
    ARCHIVE("unified_archive", FolderRole.ARCHIVE),
    SPAM("unified_spam", FolderRole.SPAM),
    TRASH("unified_trash", FolderRole.TRASH),
    ;

    companion object {
        /**
         * @return the unified folder with this [searchId], or `null` for any other id.
         */
        fun fromSearchId(searchId: String?): UnifiedFolderKind? = entries.firstOrNull { it.searchId == searchId }

        /**
         * @return the unified folder a search shows, or `null` for any other search.
         */
        fun fromSearch(search: LocalMessageSearch): UnifiedFolderKind? {
            val selection = (search.type as? LocalMessageSearchType.Unified)?.folder as? UnifiedFolderSelection.Special
                ?: return null
            return entries.firstOrNull { it.role == selection.role }
        }
    }
}

/**
 * @return a search showing [kind] across every account.
 *
 * Each account's folder of that role is resolved when the search runs; see [LocalMessageSearch.conditionsForAccount].
 */
fun createUnifiedFolderSearch(kind: UnifiedFolderKind): LocalMessageSearch =
    LocalMessageSearch.createUnifiedSearch(kind.role)

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
