package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.Preferences
import com.fsck.k9.preferences.FolderPinSettings
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.SpecialFolderUpdater
import net.thunderbird.feature.mail.folder.api.data.repository.FolderQueryRepository

class K9BackendStorageFactory(
    private val preferences: Preferences,
    private val accountManager: LegacyAccountManager,
    private val folderQueryRepository: FolderQueryRepository,
    private val messageStoreManager: MessageStoreManager,
    private val specialFolderUpdaterFactory: SpecialFolderUpdater.Factory,
    private val saveMessageDataCreator: SaveMessageDataCreator,
    private val folderPinSettings: FolderPinSettings? = null,
) : BackendStorageFactory {
    override fun createBackendStorage(accountId: AccountId): K9BackendStorage {
        val messageStore = messageStoreManager.getMessageStore(accountId)
        val folderSettingsProvider = FolderSettingsProvider(preferences, accountManager, accountId)
        val specialFolderUpdater = specialFolderUpdaterFactory.create(accountId)
        val specialFolderListener = SpecialFolderBackendFoldersRefreshListener(specialFolderUpdater)
        val autoExpandFolderListener = AutoExpandFolderBackendFoldersRefreshListener(
            accountManager = accountManager,
            accountId = accountId,
            folderQueryRepository = folderQueryRepository,
        )
        val importedPinsListener = folderPinSettings?.let { pinSettings ->
            ImportedFolderPinsRefreshListener(
                preferences = preferences,
                accountUuid = accountId.toString(),
                messageStore = messageStore,
                folderPinSettings = pinSettings,
            )
        }
        val listeners = listOfNotNull(specialFolderListener, autoExpandFolderListener, importedPinsListener)
        return K9BackendStorage(
            messageStore = messageStore,
            folderSettingsProvider = folderSettingsProvider,
            saveMessageDataCreator = saveMessageDataCreator,
            listeners = listeners,
        )
    }
}
