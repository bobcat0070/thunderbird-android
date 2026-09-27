package com.fsck.k9.preferences

import com.fsck.k9.Preferences

/**
 * Whether a list's messages are shown grouped by category, as chosen for that list.
 */
enum class CategoryGrouping {
    /**
     * Nothing chosen for this list: the app decides.
     */
    DEFAULT,
    GROUPED,
    UNGROUPED,
}

/**
 * The setting key, per folder, for [CategoryGroupingStore.getFolderGrouping].
 */
const val FOLDER_CATEGORY_GROUPING_KEY = "categoryGrouping"

/**
 * The global setting key holding [CategoryGroupingStore.getViewGrouping] for every view at once.
 */
const val VIEW_CATEGORY_GROUPING_KEY = "viewCategoryGrouping"

/**
 * Keeps the category grouping chosen for each folder and for each view that is not a folder, like the unified inbox.
 *
 * A folder's choice is kept against its server id, the way imported folder settings are, so it means the same folder
 * on every device and an import puts it straight where it is read from.
 */
interface CategoryGroupingStore {
    fun getFolderGrouping(accountUuid: String, folderServerId: String): CategoryGrouping

    fun setFolderGrouping(accountUuid: String, folderServerId: String, grouping: CategoryGrouping)

    /**
     * @param viewId the id of the search the view shows, e.g. the unified inbox's.
     */
    fun getViewGrouping(viewId: String): CategoryGrouping

    fun setViewGrouping(viewId: String, grouping: CategoryGrouping)
}

private const val ENTRY_SEPARATOR = ","
private const val VALUE_SEPARATOR = "="

internal class PreferencesCategoryGroupingStore(
    private val preferences: Preferences,
) : CategoryGroupingStore {

    override fun getFolderGrouping(accountUuid: String, folderServerId: String): CategoryGrouping {
        return preferences.storage.getStringOrNull(folderKey(accountUuid, folderServerId)).toCategoryGrouping()
    }

    override fun setFolderGrouping(accountUuid: String, folderServerId: String, grouping: CategoryGrouping) {
        val key = folderKey(accountUuid, folderServerId)

        preferences.createStorageEditor().apply {
            if (grouping == CategoryGrouping.DEFAULT) remove(key) else putString(key, grouping.name)
        }.commit()
    }

    override fun getViewGrouping(viewId: String): CategoryGrouping {
        return readViewGroupings()[viewId] ?: CategoryGrouping.DEFAULT
    }

    override fun setViewGrouping(viewId: String, grouping: CategoryGrouping) {
        val groupings = readViewGroupings().toMutableMap()
        if (grouping == CategoryGrouping.DEFAULT) groupings.remove(viewId) else groupings[viewId] = grouping

        val value = groupings.entries.joinToString(ENTRY_SEPARATOR) { (id, choice) ->
            "$id$VALUE_SEPARATOR${choice.name}"
        }

        preferences.createStorageEditor().putString(VIEW_CATEGORY_GROUPING_KEY, value).commit()
    }

    /**
     * An entry that cannot be read - from a newer version, say - is skipped rather than failing every view over it.
     */
    private fun readViewGroupings(): Map<String, CategoryGrouping> {
        return preferences.storage.getStringOrNull(VIEW_CATEGORY_GROUPING_KEY)
            .orEmpty()
            .split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val viewId = entry.substringBefore(VALUE_SEPARATOR, missingDelimiterValue = "")
                val grouping = entry.substringAfter(VALUE_SEPARATOR, missingDelimiterValue = "").toCategoryGrouping()

                if (viewId.isNotEmpty() && grouping != CategoryGrouping.DEFAULT) viewId to grouping else null
            }
            .toMap()
    }

    private fun folderKey(accountUuid: String, folderServerId: String): String {
        return "$accountUuid.$folderServerId.$FOLDER_CATEGORY_GROUPING_KEY"
    }

    private fun String?.toCategoryGrouping(): CategoryGrouping {
        return CategoryGrouping.entries.firstOrNull { it.name == this } ?: CategoryGrouping.DEFAULT
    }
}
