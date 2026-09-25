package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolderRepository
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract.UnifiedFolderRepository
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract.UseCase
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.DisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.MailDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.PinnedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayAccount
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolderType
import app.k9mail.legacy.ui.folder.DisplayFolder as LegacyDisplayFolder

internal class GetDisplayFoldersForAccount(
    private val displayFolderRepository: DisplayFolderRepository,
    private val unifiedFolderRepository: UnifiedFolderRepository,
    private val pinnedFolderRepository: PinnedFolderRepository,
    private val accountManager: LegacyAccountDtoManager,
) : UseCase.GetDisplayFoldersForAccount {
    override fun invoke(accountId: String): Flow<List<DisplayFolder>> {
        if (accountId == UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID) {
            return combine(getUnifiedFoldersFlow(), getPinnedFoldersFlow()) { unifiedFolders, pinnedFolders ->
                unifiedFolders + pinnedFolders
            }.distinctUntilChanged()
        } else {
            return displayFolderRepository.getDisplayFoldersFlow(accountId).map { displayFolders ->
                displayFolders.map { displayFolder ->
                    MailDisplayFolder(
                        accountId = accountId,
                        folder = displayFolder.folder,
                        isInTopGroup = displayFolder.isInTopGroup,
                        unreadMessageCount = displayFolder.unreadMessageCount,
                        starredMessageCount = displayFolder.starredMessageCount,
                        pathDelimiter = displayFolder.pathDelimiter,
                    )
                }
            }
        }
    }

    private fun getUnifiedFoldersFlow(): Flow<List<UnifiedDisplayFolder>> {
        return combine(UnifiedDisplayFolderType.entries.map(unifiedFolderRepository::getUnifiedDisplayFolderFlow)) {
            it.toList()
        }
    }

    /**
     * The pinned folders that still exist, in the order they were pinned.
     *
     * Pins naming an account that has since been removed are skipped before any of its folders are asked for,
     * because asking for a missing account's folders is an error rather than an empty list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun getPinnedFoldersFlow(): Flow<List<PinnedDisplayFolder>> {
        return combine(pinnedFolderRepository.getPinnedFoldersFlow(), accountManager.getAccountsFlow()) {
                pins,
                accounts,
            ->
            pins to accounts
        }.flatMapLatest { (pins, accounts) ->
            val accountsById = accounts.associateBy { it.uuid }
            val livePins = pins.filter { it.accountUuid in accountsById }
            if (livePins.isEmpty()) return@flatMapLatest flowOf(emptyList())

            val folderFlows = livePins.map { it.accountUuid }.distinct().map { accountUuid ->
                displayFolderRepository.getDisplayFoldersFlow(accountUuid).map { folders -> accountUuid to folders }
            }

            combine(folderFlows) { foldersByAccount ->
                toPinnedDisplayFolders(livePins, accountsById, foldersByAccount.toMap())
            }
        }
    }

    private fun toPinnedDisplayFolders(
        pins: List<PinnedFolder>,
        accountsById: Map<String, LegacyAccountDto>,
        foldersByAccount: Map<String, List<LegacyDisplayFolder>>,
    ): List<PinnedDisplayFolder> {
        // With one account there is nothing to tell apart, and repeating its name on every pin is noise.
        val showAccountName = accountsById.size > 1

        return pins.mapNotNull { pin ->
            val displayFolder = foldersByAccount[pin.accountUuid]?.firstOrNull { it.folder.id == pin.folderId }
                ?: return@mapNotNull null

            PinnedDisplayFolder(
                accountId = pin.accountUuid,
                accountName = accountsById[pin.accountUuid]?.displayName?.takeIf { showAccountName },
                folder = displayFolder.folder,
                unreadMessageCount = displayFolder.unreadMessageCount,
                starredMessageCount = displayFolder.starredMessageCount,
                pathDelimiter = displayFolder.pathDelimiter,
            )
        }
    }
}
