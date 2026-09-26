package net.thunderbird.app.common.feature.settings

import com.fsck.k9.preferences.FolderPinSettings
import com.fsck.k9.ui.settings.account.PinnedFolderStore
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolderRepository

/**
 * Connects settings export to the two kinds of folder pin: folders offered as one-tap filing targets, and folders
 * listed in the drawer's unified section.
 *
 * Both are stored by folder id, which export translates to the folder's server id and import translates back once
 * the folder exists here.
 */
internal class DefaultFolderPinSettings(
    private val pinnedFolderStore: PinnedFolderStore,
    private val pinnedFolderRepository: PinnedFolderRepository,
) : FolderPinSettings {

    override fun isPinnedForFiling(accountUuid: String, folderId: Long): Boolean =
        folderId in pinnedFolderStore.pinnedFolderIds(accountUuid)

    override fun isPinnedToDrawer(accountUuid: String, folderId: Long): Boolean =
        pinnedFolderRepository.isPinned(PinnedFolder(accountUuid, folderId))

    override fun pin(accountUuid: String, folderId: Long, forFiling: Boolean, toDrawer: Boolean) {
        if (forFiling) {
            pinnedFolderStore.setPinnedFolderIds(accountUuid, pinnedFolderStore.pinnedFolderIds(accountUuid) + folderId)
        }
        if (toDrawer) {
            pinnedFolderRepository.pin(PinnedFolder(accountUuid, folderId))
        }
    }
}
