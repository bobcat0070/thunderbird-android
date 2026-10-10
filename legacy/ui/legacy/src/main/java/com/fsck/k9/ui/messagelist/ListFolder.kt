package com.fsck.k9.ui.messagelist

import com.fsck.k9.mail.FolderType
import net.thunderbird.core.android.account.LegacyAccount

/**
 * A folder a message list draws from: its name, and what kind of folder it is.
 */
internal data class ListFolder(val name: String, val type: FolderType)

/**
 * Names the folder of each message, when the list holds mail from mixed folders.
 *
 * In a sender's list or a search a message could be filed anywhere, and filing it again means knowing where it is
 * now. A list from one folder says so in its title already. Nor are the inboxes of a unified inbox mixed, though
 * each account names its own differently ("Inbox", "INBOX"): every row would only say the same thing. So folders
 * of the same special kind count as one, and others as one when their names differ only in case.
 *
 * @param folder the folder a message is in, looked up once for each folder in the list.
 */
internal fun List<MessageListItem>.withFolderNames(
    folder: (account: LegacyAccount, folderId: Long) -> ListFolder?,
): List<MessageListItem> {
    val folders = distinctBy { it.account.id.toString() to it.folderId }
    if (folders.size < 2) return this

    val listFolders = folders.associate { item ->
        (item.account.id.toString() to item.folderId) to folder(item.account, item.folderId)
    }
    val isFromMixedFolders = listFolders.values.filterNotNull().distinctBy { it.kind }.size > 1

    return if (isFromMixedFolders) {
        map { item -> item.copy(folderName = listFolders[item.account.id.toString() to item.folderId]?.name) }
    } else {
        this
    }
}

private val ListFolder.kind: Any
    get() = when {
        type != FolderType.REGULAR -> type
        // IMAP reserves the name INBOX in any case, whether or not the account has recorded it as its inbox.
        name.equals(IMAP_INBOX, ignoreCase = true) -> FolderType.INBOX
        else -> name.lowercase()
    }

private const val IMAP_INBOX = "INBOX"
