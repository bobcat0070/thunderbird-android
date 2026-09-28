package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import java.util.Date
import net.thunderbird.backend.graph.api.GRAPH_ADDITIVE_FLAGS
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.toFlags

/**
 * Key under which a folder records that the last actions of the messages it already held have been read.
 */
internal const val FOLDER_EXTRA_LAST_ACTIONS_BACKFILLED = "graphLastActionsBackfilled"

/**
 * Reads, once per folder, which of the messages already stored were replied to or forwarded.
 *
 * Replied and forwarded are read for the messages each delta round reports, but that leaves out everything stored
 * before the app read them at all - and a delta round never reports a message again unless it changes. Without this,
 * mail replied to in Outlook before the update would never get its arrow.
 *
 * Only the arrows are set, never cleared, as for the delta rounds; see [GRAPH_ADDITIVE_FLAGS]. A folder is marked
 * done only once its messages were read, so a failure is tried again on the next sync.
 */
internal class GraphLastActionBackfill(
    private val reader: GraphLastActionReader,
) {
    fun backfillIfDue(
        folderServerId: String,
        backendFolder: BackendFolder,
        syncConfig: SyncConfig,
        listener: SyncListener,
    ) {
        if (backendFolder.getFolderExtraString(FOLDER_EXTRA_LAST_ACTIONS_BACKFILLED) != null) return

        val since = backendFolder.getFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_START)?.toLongOrNull()?.let(::Date)
        val messageIds = backendFolder.getMessageServerIds()
        val lastActions = if (messageIds.isEmpty()) {
            emptyMap()
        } else {
            reader.lastActionsOfStored(folderServerId, messageIds, since) ?: return
        }

        val flagsToSync = syncConfig.syncFlags intersect GRAPH_ADDITIVE_FLAGS
        for ((messageId, properties) in lastActions) {
            val remoteFlags = GraphMessage(id = messageId, singleValueExtendedProperties = properties).toFlags()
            val newFlags = (remoteFlags intersect flagsToSync) - backendFolder.getMessageFlags(messageId)

            newFlags.forEach { backendFolder.setMessageFlag(messageId, it, true) }
            if (newFlags.isNotEmpty()) listener.syncFlagChanged(folderServerId, messageId)
        }

        backendFolder.setFolderExtraString(FOLDER_EXTRA_LAST_ACTIONS_BACKFILLED, BACKFILL_DONE)
    }
}

internal const val BACKFILL_DONE = "1"
