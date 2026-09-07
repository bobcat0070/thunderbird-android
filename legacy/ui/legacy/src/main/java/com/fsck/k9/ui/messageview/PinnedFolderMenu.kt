package com.fsck.k9.ui.messageview

import android.view.Menu
import android.view.MenuItem
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import app.k9mail.legacy.mailstore.FolderRepository
import app.k9mail.legacy.ui.folder.FolderNameFormatter
import com.fsck.k9.ui.R
import com.fsck.k9.ui.settings.account.PinnedFolderStore
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.mail.folder.api.RemoteFolder

/**
 * The group the pinned items live under, so a previous set can be cleared without touching the fixed items.
 */
private const val PINNED_FOLDER_GROUP = 1

/**
 * Sorts the pinned actions after the fixed ones the toolbar always shows - delete, unread, archive - and ahead
 * of spam, move and copy, which carry a higher order for the purpose. Filing into a folder chosen on purpose
 * is worth more toolbar room than the generic move it replaces.
 */
private const val PINNED_FOLDER_ORDER = 50

/**
 * How many pinned folders are given a place in the toolbar outright.
 *
 * "If room" is not enough: the toolbar hands its action slots out in menu order, and by the time it reaches an
 * item added at runtime the budget is gone, so a pinned folder always fell into the overflow - which is the
 * menu it exists to avoid. Two is what fits beside delete and unread without crowding them; anything further
 * takes its chances with the room that is left.
 */
private const val PINNED_FOLDERS_ALWAYS_SHOWN = 2

/**
 * Adds one menu item per folder the reader has pinned, for filing a message without a folder picker.
 *
 * Most people file almost everything into one or two places, and the picker is a list of dozens standing
 * between them and a choice they have already made.
 *
 * Its own class rather than more methods on the message view fragment, which is already far past the size the
 * project allows and would only be made worse by growing it further.
 */
internal class PinnedFolderMenu(
    private val pinnedFolderStore: PinnedFolderStore,
    private val folderRepository: FolderRepository,
    private val folderNameFormatter: FolderNameFormatter,
) {

    /**
     * @param currentFolderId the folder the message is in, which is never offered - filing a message where it
     *   already is does nothing and only clutters the menu.
     * @param onFolderChosen invoked with the folder id to file into.
     */
    fun addTo(
        menu: Menu,
        account: LegacyAccountDto,
        currentFolderId: Long,
        title: (String) -> String,
        onFolderChosen: (Long) -> Unit,
    ) {
        menu.removeGroup(PINNED_FOLDER_GROUP)

        var shownInToolbar = 0

        for (folder in pinnedFolders(account)) {
            if (folder.id == currentFolderId) continue

            val showAsAction = if (shownInToolbar < PINNED_FOLDERS_ALWAYS_SHOWN) {
                shownInToolbar++
                MenuItem.SHOW_AS_ACTION_ALWAYS
            } else {
                MenuItem.SHOW_AS_ACTION_IF_ROOM
            }

            menu.add(
                PINNED_FOLDER_GROUP,
                Menu.NONE,
                PINNED_FOLDER_ORDER,
                title(folderNameFormatter.displayName(folder)),
            )
                .setIcon(Icons.Outlined.DriveFileMove)
                .setShowAsActionFlags(showAsAction)
                .setOnMenuItemClickListener {
                    onFolderChosen(folder.id)
                    true
                }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun pinnedFolders(account: LegacyAccountDto): List<RemoteFolder> {
        val pinnedIds = pinnedFolderStore.pinnedFolderIds(account.uuid)
        if (pinnedIds.isEmpty()) return emptyList()

        return try {
            folderRepository.getRemoteFolders(account.id).filter { it.id in pinnedIds }
        } catch (e: Exception) {
            // A folder list that cannot be read costs the shortcuts and nothing else; the picker still works.
            emptyList()
        }
    }
}

/**
 * The title shown for a pinned folder, kept here so the menu and its wording stay together.
 */
internal fun moveToTitle(getString: (Int, String) -> String): (String) -> String = { folderName ->
    getString(R.string.message_view_move_to_pinned, folderName)
}
