package net.thunderbird.backend.graph

import com.fsck.k9.backend.api.Backend
import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.command.CommandDelete
import net.thunderbird.backend.graph.command.CommandDownloadMessage
import net.thunderbird.backend.graph.command.CommandMoveOrCopy
import net.thunderbird.backend.graph.command.CommandRefreshFolderList
import net.thunderbird.backend.graph.command.CommandSearch
import net.thunderbird.backend.graph.command.CommandSendMessage
import net.thunderbird.backend.graph.command.CommandSetFlag
import net.thunderbird.backend.graph.command.GraphSync
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * Backend for Microsoft 365 and Outlook.com mailboxes that talks to the Microsoft Graph mail API.
 *
 * Graph replaces both halves of a traditional account: message retrieval that would otherwise use IMAP and submission
 * that would otherwise use SMTP. This matters for Microsoft 365 tenants, where IMAP and SMTP AUTH are frequently
 * disabled by policy, leaving Graph as the only available protocol.
 *
 * Messages are identified by their Graph message id. Graph reassigns that id when a message moves between folders,
 * which is why the move and copy operations report the new ids back to the caller.
 */
class GraphBackend internal constructor(
    backendStorage: BackendStorage,
    private val client: GraphApiClient,
    private val logger: Logger,
    private val pushSupport: GraphPushSupport?,
) : Backend {
    private val commandRefreshFolderList = CommandRefreshFolderList(backendStorage, client)
    private val commandSync = GraphSync(backendStorage, client, logger)
    private val commandDownloadMessage = CommandDownloadMessage(backendStorage, client)
    private val commandSetFlag = CommandSetFlag(client)
    private val commandMoveOrCopy = CommandMoveOrCopy(client)
    private val commandDelete = CommandDelete(client)
    private val commandSendMessage = CommandSendMessage(client)
    private val commandSearch = CommandSearch(client)

    override val supportsFlags = true

    /** Graph has no expunge step; a delete already removes the message from its folder. */
    override val supportsExpunge = false
    override val supportsMove = true
    override val supportsCopy = true
    override val supportsUpload = true
    override val supportsTrashFolder = true
    override val supportsSearchByDate = true

    /** Graph exposes no subscription concept for mail folders. */
    override val supportsFolderSubscriptions = false

    /**
     * Graph delivers change notifications through webhooks to a publicly reachable URL, which an app on a device
     * cannot provide. Timely delivery is instead achieved by polling frequently from the push foreground service,
     * which is only possible when the app supplied the means to schedule those polls.
     */
    override val isPushCapable = pushSupport != null

    override fun refreshFolderList(): FolderPathDelimiter = commandRefreshFolderList.refreshFolderList()

    override fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        commandSync.sync(folderServerId, syncConfig, listener)
    }

    override fun downloadMessage(syncConfig: SyncConfig, folderServerId: String, messageServerId: String) {
        commandDownloadMessage.downloadCompleteMessage(folderServerId, messageServerId)
    }

    override fun downloadMessageStructure(folderServerId: String, messageServerId: String) {
        commandDownloadMessage.downloadMessageStructure(folderServerId, messageServerId)
    }

    override fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        commandDownloadMessage.downloadCompleteMessage(folderServerId, messageServerId)
    }

    override fun setFlag(folderServerId: String, messageServerIds: List<String>, flag: Flag, newState: Boolean) {
        commandSetFlag.setFlag(messageServerIds, flag, newState)
    }

    override fun markAllAsRead(folderServerId: String) {
        commandSetFlag.markAllAsRead(folderServerId)
    }

    override fun expunge(folderServerId: String) {
        throw UnsupportedOperationException("Microsoft Graph does not support expunge")
    }

    override fun deleteMessages(folderServerId: String, messageServerIds: List<String>) {
        commandDelete.deleteMessages(messageServerIds)
    }

    override fun deleteAllMessages(folderServerId: String) {
        commandDelete.deleteAllMessages(folderServerId)
    }

    override fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = commandMoveOrCopy.moveMessages(targetFolderServerId, messageServerIds)

    override fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = commandMoveOrCopy.moveMessagesAndMarkAsRead(targetFolderServerId, messageServerIds)

    override fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = commandMoveOrCopy.copyMessages(targetFolderServerId, messageServerIds)

    override fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String> = commandSearch.search(folderServerId, query, requiredFlags, forbiddenFlags)

    /**
     * Fetching an individual part is not supported: Graph serves message content as a whole MIME document, so the
     * complete message is downloaded instead.
     */
    override fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory) {
        throw UnsupportedOperationException("Microsoft Graph does not support fetching individual message parts")
    }

    override fun findByMessageId(folderServerId: String, messageId: String): String? {
        return commandSearch.findByMessageId(folderServerId, messageId)
    }

    override fun uploadMessage(folderServerId: String, message: Message): String? {
        return commandSendMessage.uploadMessage(folderServerId, message)
    }

    override fun sendMessage(message: Message) {
        commandSendMessage.sendMessage(message)
    }

    override fun createPusher(callback: BackendPusherCallback): BackendPusher {
        val pushSupport = pushSupport ?: throw UnsupportedOperationException("Microsoft Graph push is not configured")

        return GraphBackendPusher(
            client = client,
            callback = callback,
            powerManager = pushSupport.powerManager,
            scheduler = pushSupport.scheduler,
            accountName = pushSupport.accountName,
            logger = logger,
            pollIntervalSecondsProvider = pushSupport.pollIntervalSecondsProvider,
        )
    }
}
