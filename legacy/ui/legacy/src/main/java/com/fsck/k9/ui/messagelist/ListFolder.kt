package com.fsck.k9.ui.messagelist

import net.thunderbird.core.android.account.LegacyAccount

/**
 * Names the folder of each message, when the list holds mail from more than one.
 *
 * In a sender's list or a search a message could be filed anywhere, and filing it again means knowing where it is
 * now. A list from one folder says so in its title already, and a unified inbox, whose folders all have the same
 * name, would only repeat it on every row.
 *
 * @param folderName the name of a folder, looked up once for each folder in the list.
 */
internal fun List<MessageListItem>.withFolderNames(
    folderName: (account: LegacyAccount, folderId: Long) -> String?,
): List<MessageListItem> {
    val folders = distinctBy { it.account.uuid to it.folderId }
    if (folders.size < 2) return this

    val names = folders.associate { item ->
        (item.account.uuid to item.folderId) to folderName(item.account, item.folderId)
    }
    val isFromDifferentlyNamedFolders = names.values.filterNotNull().distinct().size > 1

    return if (isFromDifferentlyNamedFolders) {
        map { item -> item.copy(folderName = names[item.account.uuid to item.folderId]) }
    } else {
        this
    }
}
