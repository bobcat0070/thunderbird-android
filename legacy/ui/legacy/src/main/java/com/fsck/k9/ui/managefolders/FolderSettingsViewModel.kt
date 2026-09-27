package com.fsck.k9.ui.managefolders

import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.preferences.CategoryGroupingStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.helper.SingleLiveEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.legacy.logging.Log

private const val NO_FOLDER_ID = 0L

class FolderSettingsViewModel(
    private val preferences: Preferences,
    private val folderDetailsRepository: FolderDetailsRepository,
    private val messagingController: MessagingController,
    private val messageStoreManager: MessageStoreManager,
    private val categoryGroupingStore: CategoryGroupingStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val actionLiveData = SingleLiveEvent<Action>()
    private var folderSettingsLiveData: LiveData<FolderSettingsResult>? = null

    private lateinit var account: LegacyAccountDto
    private var folderId: Long = NO_FOLDER_ID

    val showClearFolderInMenu: Boolean
        get() = this::account.isInitialized && folderId != NO_FOLDER_ID

    fun getFolderSettingsLiveData(accountUuid: String, folderId: Long): LiveData<FolderSettingsResult> {
        return folderSettingsLiveData ?: createFolderSettingsLiveData(accountUuid, folderId).also {
            folderSettingsLiveData = it
        }
    }

    private fun createFolderSettingsLiveData(
        accountUuid: String,
        folderId: Long,
    ): LiveData<FolderSettingsResult> {
        return liveData(context = viewModelScope.coroutineContext) {
            val account = loadAccount(accountUuid)
            val folderDetails = folderDetailsRepository.loadFolderDetails(account, folderId)
            if (folderDetails == null) {
                Log.w("Folder with ID $folderId not found")
                emit(FolderNotFound)
                return@liveData
            }

            this@FolderSettingsViewModel.account = account
            this@FolderSettingsViewModel.folderId = folderId

            val folderServerId = withContext(ioDispatcher) {
                messageStoreManager.getMessageStore(account).getFolderServerId(folderId)
            }
            val categoryGrouping = folderServerId?.let { serverId ->
                FolderSettingsDataStore.FolderCategoryGrouping(categoryGroupingStore, account.uuid, serverId)
            }

            val folderSettingsData = FolderSettingsData(
                folder = folderDetails.folder,
                dataStore = FolderSettingsDataStore(
                    folderDetailsRepository = folderDetailsRepository,
                    accountId = account.id,
                    folder = folderDetails,
                    categoryGrouping = categoryGrouping,
                ),
                hasCategoryGrouping = categoryGrouping != null,
            )
            emit(folderSettingsData)
        }
    }

    private suspend fun loadAccount(accountUuid: String): LegacyAccountDto = withContext(ioDispatcher) {
        preferences.getAccount(accountUuid) ?: error("Missing account: $accountUuid")
    }

    private suspend fun FolderDetailsRepository.loadFolderDetails(
        account: LegacyAccountDto,
        folderId: Long,
    ): FolderDetails? = withContext(ioDispatcher) {
        findById(account.id, folderId).fold(onSuccess = { it }, onFailure = { null })
    }

    fun showClearFolderConfirmationDialog() {
        sendActionEvent(Action.ShowClearFolderConfirmationDialog)
    }

    fun onClearFolderConfirmation() {
        messagingController.clearFolder(account, folderId)
    }

    fun getActionEvents(): LiveData<Action> = actionLiveData

    private fun sendActionEvent(action: Action) {
        actionLiveData.value = action
    }
}

sealed class FolderSettingsResult
object FolderNotFound : FolderSettingsResult()
data class FolderSettingsData(
    val folder: Folder,
    val dataStore: FolderSettingsDataStore,
    val hasCategoryGrouping: Boolean = false,
) : FolderSettingsResult()

sealed class Action {
    object ShowClearFolderConfirmationDialog : Action()
}
