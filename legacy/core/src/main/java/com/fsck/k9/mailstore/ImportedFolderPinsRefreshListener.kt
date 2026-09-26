package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageStore
import com.fsck.k9.Preferences
import com.fsck.k9.preferences.FolderPinSettings

private const val PINNED_FOR_FILING = "pinnedForFiling"
private const val PINNED_TO_DRAWER = "pinnedToDrawer"

/**
 * Applies folder pins that arrived with a settings import.
 *
 * An import records pins against the folder's server id, because the folder may not exist on this device yet and a
 * folder id from another device means nothing here. Once a folder list refresh has created the folder, its pin is
 * applied and the pending entry removed - the same path the other imported folder settings take.
 *
 * A pin whose folder has not appeared yet stays pending for the next refresh, rather than being dropped because the
 * first sync happened to finish before the folder list was complete.
 */
internal class ImportedFolderPinsRefreshListener(
    private val preferences: Preferences,
    private val accountUuid: String,
    private val messageStore: MessageStore,
    private val folderPinSettings: FolderPinSettings,
) : BackendFoldersRefreshListener {

    override fun onBeforeFolderListRefresh() = Unit

    override suspend fun onAfterFolderListRefresh() {
        val storage = preferences.storage
        val prefix = "$accountUuid."
        val pendingKeys = storage.getAll().keys.filter { key ->
            key.startsWith(prefix) && (key.endsWith(".$PINNED_FOR_FILING") || key.endsWith(".$PINNED_TO_DRAWER"))
        }
        if (pendingKeys.isEmpty()) return

        // Server ids may contain dots themselves, so the setting name is taken off the end rather than split on.
        val keysByServerId = pendingKeys.groupBy { key -> key.removePrefix(prefix).substringBeforeLast('.') }

        val editor = preferences.createStorageEditor()
        for ((serverId, keys) in keysByServerId) {
            val folderId = messageStore.getFolderId(serverId) ?: continue

            folderPinSettings.pin(
                accountUuid = accountUuid,
                folderId = folderId,
                forFiling = storage.getBoolean("$prefix$serverId.$PINNED_FOR_FILING", false),
                toDrawer = storage.getBoolean("$prefix$serverId.$PINNED_TO_DRAWER", false),
            )
            keys.forEach { editor.remove(it) }
        }
        editor.commit()
    }
}
