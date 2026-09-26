package com.fsck.k9.preferences

import app.k9mail.legacy.mailstore.RemoteFolderDetails
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.mail.folder.api.data.repository.RemoteFolderDetailsRepository

class FolderSettingsProvider(
    private val remoteFolderDetailsRepository: RemoteFolderDetailsRepository,
    private val folderPinSettings: FolderPinSettings? = null,
) {
    suspend fun getFolderSettings(account: LegacyAccountDto): List<FolderSettings> {
        return remoteFolderDetailsRepository
            .getAllByAccountId(account.id)
            .fold(
                onSuccess = { it },
                onFailure = { error ->
                    when (val throwable = error.throwable) {
                        null -> error("Unknown error while fetching remote folder details settings. Error: $error")
                        else -> throw throwable
                    }
                },
            )
            .map { it.toFolderSettings(account.uuid) }
            .filterNot { it.containsOnlyDefaultValues() }
    }

    // A pinned folder is worth exporting even when every other setting is at its default.
    private fun FolderSettings.containsOnlyDefaultValues(): Boolean {
        return isInTopGroup == getDefaultValue("inTopGroup") &&
            isIntegrate == getDefaultValue("integrate") &&
            isSyncEnabled == getDefaultValue("syncEnabled") &&
            isVisible == getDefaultValue("visible") &&
            isNotificationsEnabled == getDefaultValue("notificationsEnabled") &&
            isPushEnabled == getDefaultValue("pushEnabled") &&
            isPinnedForFiling == getDefaultValue("pinnedForFiling") &&
            isPinnedToDrawer == getDefaultValue("pinnedToDrawer")
    }

    private fun getDefaultValue(key: String): Any? {
        val versionedSetting = FolderSettingsDescriptions.SETTINGS[key] ?: error("Key not found: $key")
        val highestVersion = versionedSetting.lastKey()
        val setting = versionedSetting[highestVersion] ?: error("Setting description not found: $key")
        return setting.defaultValue
    }

    private fun RemoteFolderDetails.toFolderSettings(accountUuid: String): FolderSettings {
        return FolderSettings(
            folder.serverId,
            isInTopGroup,
            isIntegrate,
            isSyncEnabled,
            isVisible,
            isNotificationsEnabled,
            isPushEnabled,
            isPinnedForFiling = folderPinSettings?.isPinnedForFiling(accountUuid, folder.id) == true,
            isPinnedToDrawer = folderPinSettings?.isPinnedToDrawer(accountUuid, folder.id) == true,
        )
    }
}

data class FolderSettings(
    val serverId: String,
    val isInTopGroup: Boolean,
    val isIntegrate: Boolean,
    val isSyncEnabled: Boolean,
    val isVisible: Boolean,
    val isNotificationsEnabled: Boolean,
    val isPushEnabled: Boolean,
    val isPinnedForFiling: Boolean = false,
    val isPinnedToDrawer: Boolean = false,
)
