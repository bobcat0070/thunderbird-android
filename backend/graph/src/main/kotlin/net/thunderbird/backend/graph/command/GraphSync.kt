package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.MessageDownloadState
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.hasBodyPreview
import net.thunderbird.backend.graph.api.receivedDate
import net.thunderbird.backend.graph.api.toEnvelopeMessage
import net.thunderbird.backend.graph.api.toFlags
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger

/**
 * Key under which the Graph delta token for a folder is stored.
 */
internal const val FOLDER_EXTRA_DELTA_LINK = "graphDeltaLink"

/**
 * Key under which the start of the synchronized window is stored, as epoch milliseconds.
 *
 * A delta round reports every change in the folder, including to mail older than what is held locally. Remembering
 * where the window starts keeps the local store a window on recent mail instead of slowly growing backwards as old
 * messages are edited or moved.
 */
internal const val FOLDER_EXTRA_SYNC_WINDOW_START = "graphSyncWindowStart"

/**
 * Key under which the visible limit used for the last full round is stored.
 *
 * Incremental rounds only report changes, so they can never reach further back in time. When the user asks for more
 * messages the limit grows, and the folder has to be enumerated again to widen the window.
 */
internal const val FOLDER_EXTRA_SYNC_WINDOW_LIMIT = "graphSyncWindowLimit"

/**
 * Key under which the format of the locally stored messages is recorded.
 */
internal const val FOLDER_EXTRA_SYNC_FORMAT = "graphSyncFormat"

/**
 * Current storage format.
 *
 * Incremental rounds only report changes, so a folder synchronized by an older version keeps whatever was stored
 * then. Raising this forces one full round, which is how an improvement to what is stored per message reaches mail
 * that was already synchronized. Version 2 added the message list preview text, version 3 the headers
 * used to classify a message.
 */
internal const val SYNC_FORMAT_VERSION = 3

/**
 * Synchronizes a single folder with Microsoft Graph.
 *
 * Synchronization is incremental: the first round enumerates the folder and stores a delta token, and later rounds
 * present that token so Graph returns only what changed. An idle folder therefore costs one request that returns
 * nothing, which is what makes a short check interval affordable.
 *
 * Only message envelopes are transferred. The full MIME content of a message is downloaded on demand, keeping a sync
 * proportional to the number of changes rather than to the size of the mailbox.
 */
internal class GraphSync(
    private val backendStorage: BackendStorage,
    private val client: GraphApiClient,
    private val logger: Logger,
) {
    private val deltaReader = GraphDeltaReader(client)

    @Suppress("TooGenericExceptionCaught")
    fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        try {
            listener.syncStarted(folderServerId)

            val backendFolder = backendStorage.getFolder(folderServerId)
            val visibleLimit = backendFolder.visibleLimit.takeIf { it > 0 } ?: syncConfig.defaultVisibleLimit

            listener.syncHeadersStarted(folderServerId)

            // A larger visible limit means the user asked for older mail than the delta stream can reach, and an
            // older storage format means the stored messages need refreshing. Either way the folder is enumerated
            // again instead of resumed.
            val syncedLimit = backendFolder.getFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_LIMIT)?.toIntOrNull() ?: 0
            val syncedFormat = backendFolder.getFolderExtraString(FOLDER_EXTRA_SYNC_FORMAT)?.toIntOrNull() ?: 0
            // An older storage format means the stored messages are missing something this version keeps, so
            // the round re-saves what it finds rather than only what is new.
            val isFormatUpgrade = syncedFormat < SYNC_FORMAT_VERSION
            val canResume = visibleLimit <= syncedLimit && !isFormatUpgrade
            val storedDeltaLink = backendFolder.getFolderExtraString(FOLDER_EXTRA_DELTA_LINK)?.takeIf { canResume }
            val isIncremental = storedDeltaLink != null
            val round = runRound(folderServerId, storedDeltaLink, syncConfig, visibleLimit)

            listener.syncAuthenticationSuccess()

            val messagesToSave = selectMessagesToSave(backendFolder, round.messages, visibleLimit, isIncremental)
            val newMessageCount = saveMessages(
                folderServerId = folderServerId,
                backendFolder = backendFolder,
                messages = messagesToSave,
                listener = listener,
                refreshStoredMessages = isFormatUpgrade,
            )

            listener.syncHeadersFinished(
                folderServerId = folderServerId,
                totalMessagesInMailbox = round.messages.size,
                numNewMessages = newMessageCount,
            )

            updateFlags(folderServerId, backendFolder, round.messages, syncConfig, listener)

            if (syncConfig.syncRemoteDeletions) {
                removeMessages(folderServerId, backendFolder, round.removedMessageServerIds, listener)
            }

            // Only a completed round may be resumed; otherwise the next sync starts a fresh one.
            round.deltaLink?.let { backendFolder.setFolderExtraString(FOLDER_EXTRA_DELTA_LINK, it) }

            // An incremental round only reports changes, so it says nothing about whether older mail remains on
            // the server. The answer from the last full round is left in place.
            if (!isIncremental) {
                backendFolder.setMoreMessages(moreMessagesFor(round.messages, visibleLimit))
            }
            backendFolder.setLastChecked(System.currentTimeMillis())
            backendFolder.setStatus(null)

            listener.folderStatusChanged(folderServerId)
            listener.syncFinished(folderServerId)
        } catch (e: Exception) {
            // The message is deliberately generic: Graph error payloads can echo message metadata.
            logger.error(throwable = e) { "Failed to synchronize folder via Microsoft Graph" }

            backendStorage.getFolder(folderServerId).setStatus(e.messageForStatus())
            listener.syncFailed(folderServerId, e.messageForStatus(), e)
        }
    }

    /**
     * Resumes the stored delta stream, falling back to a fresh one if the stored token is no longer accepted.
     *
     * Graph expires delta tokens, and a folder that was moved or recreated invalidates them too. Rather than failing
     * the sync, the folder is enumerated again.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runRound(
        folderServerId: String,
        storedDeltaLink: String?,
        syncConfig: SyncConfig,
        visibleLimit: Int,
    ): GraphDeltaRound {
        if (storedDeltaLink != null) {
            try {
                return deltaReader.incrementalRound(storedDeltaLink)
            } catch (e: Exception) {
                logger.debug(throwable = e) { "Delta token rejected; starting a new delta round" }

                backendStorage.getFolder(folderServerId).setFolderExtraString(FOLDER_EXTRA_DELTA_LINK, null)
            }
        }

        return deltaReader.initialRound(folderServerId, syncConfig.earliestPollDate, visibleLimit)
    }

    /**
     * Picks the messages worth storing locally.
     *
     * An initial round is truncated to the visible limit, and the oldest message it kept marks the start of the
     * synchronized window. Later rounds ignore anything older than that mark, so an edit to a years-old message does
     * not pull it into the local store.
     */
    private fun selectMessagesToSave(
        backendFolder: BackendFolder,
        messages: List<GraphMessage>,
        visibleLimit: Int,
        isIncremental: Boolean,
    ): List<GraphMessage> {
        if (!isIncremental) {
            return messages.take(visibleLimit).also { rememberSyncWindow(backendFolder, it, visibleLimit) }
        }

        val windowStart = backendFolder.getFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_START)?.toLongOrNull()

        return if (windowStart == null) {
            messages.take(visibleLimit)
        } else {
            messages.filter { message ->
                val receivedDate = message.receivedDate()?.time

                receivedDate == null || receivedDate >= windowStart
            }
        }
    }

    private fun rememberSyncWindow(
        backendFolder: BackendFolder,
        messages: List<GraphMessage>,
        visibleLimit: Int,
    ) {
        backendFolder.setFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_LIMIT, visibleLimit.toString())
        backendFolder.setFolderExtraString(FOLDER_EXTRA_SYNC_FORMAT, SYNC_FORMAT_VERSION.toString())

        val windowStart = messages.mapNotNull { it.receivedDate()?.time }.minOrNull() ?: return

        backendFolder.setFolderExtraString(FOLDER_EXTRA_SYNC_WINDOW_START, windowStart.toString())
    }

    /**
     * Stores envelopes for messages that are not held locally yet.
     *
     * @param refreshStoredMessages re-save messages that are already held locally, so an improvement to what
     *   is stored per message reaches mail that was synchronized by an earlier version.
     * @return the number of messages that were added.
     */
    private fun saveMessages(
        folderServerId: String,
        backendFolder: BackendFolder,
        messages: List<GraphMessage>,
        listener: SyncListener,
        refreshStoredMessages: Boolean,
    ): Int {
        val localMessageServerIds = backendFolder.getMessageServerIds()
        val newMessages = messages.filter { it.id !in localMessageServerIds }

        // A stored message is refreshed on a format upgrade, and otherwise only when it is missing the preview
        // text the message list needs. Re-saving is cheap here because the envelope is already in hand.
        val messagesToRefresh = messages.filter { graphMessage ->
            graphMessage.id in localMessageServerIds &&
                (
                    refreshStoredMessages ||
                        (graphMessage.hasBodyPreview() && backendFolder.isEnvelopeOnly(graphMessage.id))
                    )
        }

        messagesToRefresh.forEach { graphMessage ->
            val downloadState = if (graphMessage.hasBodyPreview()) {
                MessageDownloadState.PARTIAL
            } else {
                MessageDownloadState.ENVELOPE
            }

            backendFolder.saveMessage(graphMessage.toEnvelopeMessage(), downloadState)
        }

        newMessages.forEachIndexed { index, graphMessage ->
            // A preview body makes the stored message partial rather than headers-only, which is what lets the
            // message list show its first lines. Opening it still downloads the complete message.
            val downloadState = if (graphMessage.hasBodyPreview()) {
                MessageDownloadState.PARTIAL
            } else {
                MessageDownloadState.ENVELOPE
            }

            backendFolder.saveMessage(graphMessage.toEnvelopeMessage(), downloadState)

            listener.syncNewMessage(
                folderServerId = folderServerId,
                messageServerId = graphMessage.id,
                isOldMessage = false,
            )
            listener.syncProgress(folderServerId, completed = index + 1, total = newMessages.size)
        }

        return newMessages.size
    }

    /**
     * Whether only the headers of a message are stored, meaning no body was ever downloaded for it.
     */
    private fun BackendFolder.isEnvelopeOnly(messageServerId: String): Boolean {
        val flags = getMessageFlags(messageServerId)

        return Flag.X_DOWNLOADED_FULL !in flags && Flag.X_DOWNLOADED_PARTIAL !in flags
    }

    /**
     * Applies remote flag changes to messages that are already stored locally.
     */
    private fun updateFlags(
        folderServerId: String,
        backendFolder: BackendFolder,
        messages: List<GraphMessage>,
        syncConfig: SyncConfig,
        listener: SyncListener,
    ) {
        for (graphMessage in messages) {
            if (!backendFolder.isMessagePresent(graphMessage.id)) continue

            val remoteFlags = graphMessage.toFlags()
            val localFlags = backendFolder.getMessageFlags(graphMessage.id)
            var changed = false

            for (flag in syncConfig.syncFlags) {
                val remoteValue = flag in remoteFlags
                if (remoteValue != flag in localFlags) {
                    backendFolder.setMessageFlag(graphMessage.id, flag, remoteValue)
                    changed = true
                }
            }

            if (changed) {
                listener.syncFlagChanged(folderServerId, graphMessage.id)
            }
        }
    }

    /**
     * Removes local copies of messages that Graph reported as gone from the folder.
     */
    private fun removeMessages(
        folderServerId: String,
        backendFolder: BackendFolder,
        removedMessageServerIds: List<String>,
        listener: SyncListener,
    ) {
        val presentMessageServerIds = removedMessageServerIds.filter { backendFolder.isMessagePresent(it) }
        if (presentMessageServerIds.isEmpty()) return

        backendFolder.destroyMessages(presentMessageServerIds)

        for (messageServerId in presentMessageServerIds) {
            listener.syncRemovedMessage(folderServerId, messageServerId)
        }
    }

    private fun moreMessagesFor(messages: List<GraphMessage>, visibleLimit: Int): BackendFolder.MoreMessages {
        return if (messages.size >= visibleLimit) {
            BackendFolder.MoreMessages.TRUE
        } else {
            BackendFolder.MoreMessages.FALSE
        }
    }

    /**
     * A status string that is safe to persist and display; it never carries server-provided message data.
     */
    private fun Exception.messageForStatus(): String {
        return when (this) {
            is MessagingException -> message ?: "Synchronization failed"
            else -> "Synchronization failed"
        }
    }
}
