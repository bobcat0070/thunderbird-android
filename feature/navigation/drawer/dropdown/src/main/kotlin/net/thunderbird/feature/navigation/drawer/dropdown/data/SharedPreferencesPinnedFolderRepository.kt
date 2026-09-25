package net.thunderbird.feature.navigation.drawer.dropdown.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolderRepository

private const val PREFERENCES_NAME = "drawer_pinned_folders"
private const val KEY_PINNED = "pinned"

/**
 * Separates one pinned folder from the next in the stored value.
 *
 * A single string rather than a string set, because a set does not keep order and the order is the one the user
 * pinned in. Neither separator can occur in an account uuid or a folder id.
 */
private const val ENTRY_SEPARATOR = "\n"
private const val FIELD_SEPARATOR = "|"

/**
 * Keeps the drawer's pinned folders on this device.
 *
 * Beside the accounts rather than inside them, like the folders pinned for filing: where a person likes to go is
 * about how they use this device, not part of an account's identity, and should not travel through settings export.
 *
 * A folder that has since been deleted stays in the list until it is unpinned, and is simply not shown - the drawer
 * only lists pins it can find a folder for. Removing it here would mean watching every account for deletions.
 */
internal class SharedPreferencesPinnedFolderRepository(context: Context) : PinnedFolderRepository {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val pinnedFolders = MutableStateFlow(read())

    override fun getPinnedFoldersFlow(): Flow<List<PinnedFolder>> = pinnedFolders.asStateFlow()

    override fun isPinned(folder: PinnedFolder): Boolean = folder in pinnedFolders.value

    override fun pin(folder: PinnedFolder) {
        update { current -> if (folder in current) current else current + folder }
    }

    override fun unpin(folder: PinnedFolder) {
        update { current -> current - folder }
    }

    private fun update(transform: (List<PinnedFolder>) -> List<PinnedFolder>) {
        pinnedFolders.update { current ->
            transform(current).also { write(it) }
        }
    }

    private fun read(): List<PinnedFolder> {
        return preferences.getString(KEY_PINNED, null)
            .orEmpty()
            .split(ENTRY_SEPARATOR)
            .mapNotNull { entry -> entry.toPinnedFolder() }
            .distinct()
    }

    private fun write(folders: List<PinnedFolder>) {
        val value = folders.joinToString(ENTRY_SEPARATOR) { "${it.accountUuid}$FIELD_SEPARATOR${it.folderId}" }

        preferences.edit().putString(KEY_PINNED, value).apply()
    }
}

/**
 * @return the folder an entry names, or `null` for an entry that cannot be read - which is dropped rather than
 *   failing the whole list over one bad value.
 */
private fun String.toPinnedFolder(): PinnedFolder? {
    val accountUuid = substringBefore(FIELD_SEPARATOR, missingDelimiterValue = "")
    val folderId = substringAfter(FIELD_SEPARATOR, missingDelimiterValue = "").toLongOrNull()

    return if (accountUuid.isNotEmpty() && folderId != null) PinnedFolder(accountUuid, folderId) else null
}
