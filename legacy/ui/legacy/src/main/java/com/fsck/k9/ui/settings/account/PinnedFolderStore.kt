package com.fsck.k9.ui.settings.account

import android.content.Context
import android.content.SharedPreferences

private const val PREFERENCES_NAME = "pinned_move_folders"

/**
 * The folders a reader files mail into often enough to want one tap for.
 *
 * Moving a message otherwise means opening a folder picker and finding the same folder every time. Most people
 * file almost everything into one or two places, so the picker is a list of dozens standing between them and a
 * choice they have already made.
 *
 * Kept beside the account rather than inside it: this is a convenience about how one person works, not part of
 * the account's identity or its server settings, and it should not travel through account import and export as
 * though it were.
 */
class PinnedFolderStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * @return the pinned folder ids for this account, in no particular order.
     */
    fun pinnedFolderIds(accountUuid: String): Set<Long> {
        return read(accountUuid).mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
    }

    fun setPinnedFolderIds(accountUuid: String, folderIds: Set<Long>) {
        preferences.edit()
            .putStringSet(accountUuid, folderIds.mapTo(mutableSetOf()) { it.toString() })
            .apply()
    }

    /**
     * @return the stored ids as the strings a preference works in.
     */
    fun pinnedFolderValues(accountUuid: String): Set<String> = read(accountUuid)

    // The returned set is owned by SharedPreferences and must not be mutated, so every write builds a new one.
    private fun read(accountUuid: String): Set<String> =
        preferences.getStringSet(accountUuid, emptySet()).orEmpty()
}
