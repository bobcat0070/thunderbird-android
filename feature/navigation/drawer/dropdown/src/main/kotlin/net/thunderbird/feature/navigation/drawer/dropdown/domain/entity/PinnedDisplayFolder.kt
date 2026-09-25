package net.thunderbird.feature.navigation.drawer.dropdown.domain.entity

import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * A folder of one account, pinned to the unified list.
 *
 * Its own kind of entry rather than a [MailDisplayFolder], because it is listed flat: a pinned "Projects/2026" must
 * not be nested under a "Projects" the unified list does not contain.
 *
 * Shares its id with the same folder's entry in the account's own list, so opening it highlights it wherever it is
 * shown.
 *
 * @param accountName shown beside the folder's name when there is more than one account to tell apart, and
 *   `null` otherwise.
 */
internal data class PinnedDisplayFolder(
    val accountId: String,
    val accountName: String?,
    val folder: Folder,
    override val unreadMessageCount: Int,
    override val starredMessageCount: Int,
    override val pathDelimiter: FolderPathDelimiter,
) : DisplayFolder {
    override val id: String = createMailDisplayAccountFolderId(accountId, folder.id)
}
