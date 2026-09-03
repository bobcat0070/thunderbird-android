package net.thunderbird.backend.graph

import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.mail.power.PowerManager
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphFolderState
import net.thunderbird.backend.graph.api.readFolderStates
import net.thunderbird.core.logging.Logger

/**
 * Shortest interval between polls.
 *
 * Graph offers no way for a client to be notified of new mail, so the only route to timely delivery is asking often.
 * The floor keeps an aggressive setting from turning into a request storm.
 */
internal const val MIN_POLL_INTERVAL_SECONDS = 15L

/**
 * Keeps a Microsoft Graph account up to date without waiting for the periodic background sync.
 *
 * Graph has no equivalent of IMAP IDLE: change notifications are delivered to a public HTTPS endpoint, which a device
 * cannot host. This pusher therefore polls, but it runs inside the push foreground service rather than as background
 * work, which is what lets it check far more often than the fifteen-minute floor the platform imposes on periodic
 * work.
 *
 * Polling is deliberately cheap. Rather than synchronizing, each poll reads only the message counts of the pushed
 * folders, batched into a single request, and reports a folder as changed when its counts moved. The real
 * synchronization is left to the caller, which keeps the delta token this pusher would otherwise consume.
 */
class GraphBackendPusher internal constructor(
    private val client: GraphApiClient,
    private val callback: BackendPusherCallback,
    private val powerManager: PowerManager,
    private val scheduler: GraphPushScheduler,
    private val accountName: String,
    private val logger: Logger,
    private val pollIntervalSecondsProvider: () -> Long,
) : BackendPusher {

    private val lock = Any()
    private var folderServerIds: List<String> = emptyList()
    private var lastSeenStates: Map<String, GraphFolderState> = emptyMap()
    private var isStarted = false

    override fun start() {
        synchronized(lock) {
            if (isStarted) return
            isStarted = true
        }

        logger.verbose { "Starting Graph push for $accountName" }
        schedulePoll()
    }

    override fun updateFolders(folderServerIds: Collection<String>) {
        synchronized(lock) {
            this.folderServerIds = folderServerIds.toList()
            // Drop remembered state for folders that are no longer pushed, so re-enabling one starts fresh rather
            // than comparing against a stale count.
            lastSeenStates = lastSeenStates.filterKeys { it in folderServerIds }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!isStarted) return
            isStarted = false
        }

        logger.verbose { "Stopping Graph push for $accountName" }
        scheduler.cancel()
    }

    /**
     * Checks immediately, used when connectivity returns.
     */
    override fun reconnect() {
        logger.verbose { "Reconnecting Graph push for $accountName" }
        scheduler.cancel()
        schedulePoll(delaySeconds = 0)
    }

    private fun schedulePoll(delaySeconds: Long = pollInterval()) {
        scheduler.schedule(delaySeconds) { poll() }
    }

    private fun pollInterval(): Long = pollIntervalSecondsProvider().coerceAtLeast(MIN_POLL_INTERVAL_SECONDS)

    @Suppress("TooGenericExceptionCaught")
    private fun poll() {
        val foldersToCheck = synchronized(lock) {
            if (!isStarted) return
            folderServerIds
        }

        if (foldersToCheck.isEmpty()) {
            schedulePoll()
            return
        }

        // The device must stay awake long enough to finish the request, or the result arrives after it sleeps.
        val wakeLock = powerManager.newWakeLock("GraphBackendPusher-$accountName")
        wakeLock.acquire()

        try {
            val changedFolders = findChangedFolders(foldersToCheck)

            for (folderServerId in changedFolders) {
                callback.onPushEvent(folderServerId)
            }
        } catch (e: Exception) {
            // The message is deliberately generic: Graph error payloads can echo message metadata.
            logger.debug(throwable = e) { "Graph push poll failed" }

            callback.onPushError(e)
        } finally {
            wakeLock.release()

            if (synchronized(lock) { isStarted }) {
                schedulePoll()
            }
        }
    }

    /**
     * @return the folders whose contents changed since the previous poll.
     */
    private fun findChangedFolders(foldersToCheck: List<String>): List<String> {
        val states = client.readFolderStates(foldersToCheck)

        return synchronized(lock) {
            val previousStates = lastSeenStates
            lastSeenStates = previousStates + states

            states.mapNotNull { (folderServerId, state) ->
                val previous = previousStates[folderServerId]

                // The first poll establishes a baseline; reporting everything as changed then would trigger a
                // pointless sync of every pushed folder each time push starts.
                folderServerId.takeIf { previous != null && previous != state }
            }
        }
    }
}
