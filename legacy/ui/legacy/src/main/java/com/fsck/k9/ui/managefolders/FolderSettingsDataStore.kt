package com.fsck.k9.ui.managefolders

import com.fsck.k9.preferences.CategoryGrouping
import com.fsck.k9.preferences.CategoryGroupingStore
import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository

/**
 * The key of the folder's category grouping choice, which lives with the choice made from the message list rather
 * than in the folder's row.
 */
const val PREFERENCE_CATEGORY_GROUPING = "folder_settings_category_grouping"

class FolderSettingsDataStore(
    private val folderDetailsRepository: FolderDetailsRepository,
    private val accountId: AccountId,
    private var folder: FolderDetails,
    private val categoryGrouping: FolderCategoryGrouping? = null,
    private val saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PreferenceDataStore() {

    /**
     * Where a folder's category grouping choice is kept, and which folder it is - by server id, the way the choice
     * made from the message list is kept. `null` for a folder that exists only on the device.
     */
    class FolderCategoryGrouping(
        val store: CategoryGroupingStore,
        val accountUuid: String,
        val folderServerId: String,
    )

    override fun getString(key: String?, defValue: String?): String? {
        return when (key) {
            PREFERENCE_CATEGORY_GROUPING -> categoryGrouping?.let { grouping ->
                grouping.store.getFolderGrouping(grouping.accountUuid, grouping.folderServerId).name
            } ?: defValue

            else -> error("Unknown key: $key")
        }
    }

    override fun putString(key: String?, value: String?) {
        when (key) {
            PREFERENCE_CATEGORY_GROUPING -> {
                val grouping = categoryGrouping ?: return
                val choice = CategoryGrouping.entries.firstOrNull { it.name == value } ?: CategoryGrouping.DEFAULT

                grouping.store.setFolderGrouping(grouping.accountUuid, grouping.folderServerId, choice)
            }

            else -> error("Unknown key: $key")
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return when (key) {
            "folder_settings_in_top_group" -> folder.isInTopGroup
            "folder_settings_include_in_integrated_inbox" -> folder.isIntegrate
            "folder_settings_sync" -> folder.isSyncEnabled
            "folder_settings_notifications" -> folder.isNotificationsEnabled
            "folder_settings_push" -> folder.isPushEnabled
            "folder_settings_visible" -> folder.isVisible
            else -> error("Unknown key: $key")
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        return when (key) {
            "folder_settings_in_top_group" -> updateFolder(folder.copy(isInTopGroup = value))
            "folder_settings_include_in_integrated_inbox" -> updateFolder(folder.copy(isIntegrate = value))
            "folder_settings_sync" -> updateFolder(folder.copy(isSyncEnabled = value))
            "folder_settings_notifications" -> updateFolder(folder.copy(isNotificationsEnabled = value))
            "folder_settings_push" -> updateFolder(folder.copy(isPushEnabled = value))
            "folder_settings_visible" -> updateFolder(folder.copy(isVisible = value))
            else -> error("Unknown key: $key")
        }
    }

    private fun updateFolder(newFolder: FolderDetails) {
        folder = newFolder
        saveScope.launch {
            folderDetailsRepository.update(accountId, newFolder)
        }
    }
}
