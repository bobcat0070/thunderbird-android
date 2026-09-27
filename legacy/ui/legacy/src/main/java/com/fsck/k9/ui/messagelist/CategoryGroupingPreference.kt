package com.fsck.k9.ui.messagelist

import net.thunderbird.feature.mail.folder.api.FolderType
import com.fsck.k9.preferences.CategoryGrouping
import com.fsck.k9.preferences.CategoryGroupingStore
import net.thunderbird.feature.search.legacy.UnifiedFolderKind

/**
 * Which list a category grouping choice belongs to.
 */
internal sealed interface CategoryGroupingScope {
    data class Folder(val accountUuid: String, val serverId: String, val type: FolderType) : CategoryGroupingScope

    /**
     * A list that is not one folder but has a name of its own, like the unified inbox.
     */
    data class View(val viewId: String) : CategoryGroupingScope

    /**
     * Any other list, which follows the app-wide setting.
     */
    data object Elsewhere : CategoryGroupingScope
}

/**
 * Folder types whose mail is grouped only when asked. Categories sort mail by what sent it, and here that is the user.
 */
private val UNGROUPED_FOLDER_TYPES = setOf(FolderType.SENT, FolderType.DRAFTS, FolderType.OUTBOX)
private val UNGROUPED_UNIFIED_FOLDERS = setOf(UnifiedFolderKind.SENT, UnifiedFolderKind.DRAFTS)

/**
 * Decides whether a list is grouped by category.
 *
 * Each folder and view keeps its own choice once the user makes one. Until then it follows the app-wide setting,
 * except the folders holding the user's own mail, which start ungrouped.
 *
 * @param isGroupedByDefault the app-wide setting.
 * @param setGroupedByDefault changes the app-wide setting, for lists that have no choice of their own.
 */
internal class CategoryGroupingPreference(
    private val store: CategoryGroupingStore,
    private val isGroupedByDefault: () -> Boolean,
    private val setGroupedByDefault: (Boolean) -> Unit,
) {
    fun isGrouped(scope: CategoryGroupingScope): Boolean {
        return when (scope) {
            is CategoryGroupingScope.Folder -> {
                store.getFolderGrouping(scope.accountUuid, scope.serverId)
                    .resolve { scope.type !in UNGROUPED_FOLDER_TYPES && isGroupedByDefault() }
            }

            is CategoryGroupingScope.View -> {
                val unifiedFolder = UnifiedFolderKind.fromSearchId(scope.viewId)

                store.getViewGrouping(scope.viewId)
                    .resolve { unifiedFolder !in UNGROUPED_UNIFIED_FOLDERS && isGroupedByDefault() }
            }

            CategoryGroupingScope.Elsewhere -> isGroupedByDefault()
        }
    }

    fun setGrouped(scope: CategoryGroupingScope, isGrouped: Boolean) {
        val grouping = if (isGrouped) CategoryGrouping.GROUPED else CategoryGrouping.UNGROUPED

        when (scope) {
            is CategoryGroupingScope.Folder -> store.setFolderGrouping(scope.accountUuid, scope.serverId, grouping)
            is CategoryGroupingScope.View -> store.setViewGrouping(scope.viewId, grouping)
            CategoryGroupingScope.Elsewhere -> setGroupedByDefault(isGrouped)
        }
    }

    private inline fun CategoryGrouping.resolve(default: () -> Boolean): Boolean {
        return when (this) {
            CategoryGrouping.GROUPED -> true
            CategoryGrouping.UNGROUPED -> false
            CategoryGrouping.DEFAULT -> default()
        }
    }
}
