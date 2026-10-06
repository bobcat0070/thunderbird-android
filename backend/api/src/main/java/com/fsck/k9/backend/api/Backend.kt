package com.fsck.k9.backend.api

import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

interface Backend {
    val supportsFlags: Boolean
    val supportsExpunge: Boolean
    val supportsMove: Boolean
    val supportsCopy: Boolean
    val supportsUpload: Boolean
    val supportsTrashFolder: Boolean
    val supportsSearchByDate: Boolean
    val supportsFolderSubscriptions: Boolean
    val isPushCapable: Boolean

    /**
     * Whether sending a message also files it in the server's Sent folder, so the app must not upload a copy of its
     * own.
     */
    val savesSentMessages: Boolean
        get() = false

    /**
     * Whether messages carry categories that are kept on the server and can be changed from here, as Outlook's
     * are.
     */
    val supportsServerCategories: Boolean
        get() = false

    @Throws(MessagingException::class)
    fun refreshFolderList(): FolderPathDelimiter?

    // TODO: Add a way to cancel the sync process
    fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener)

    @Throws(MessagingException::class)
    fun downloadMessage(syncConfig: SyncConfig, folderServerId: String, messageServerId: String)

    @Throws(MessagingException::class)
    fun downloadMessageStructure(folderServerId: String, messageServerId: String)

    @Throws(MessagingException::class)
    suspend fun downloadCompleteMessage(folderServerId: String, messageServerId: String)

    @Throws(MessagingException::class)
    fun setFlag(folderServerId: String, messageServerIds: List<String>, flag: Flag, newState: Boolean)

    /**
     * Replaces the categories of the given messages on the server.
     *
     * Only called for a backend that [supportsServerCategories].
     */
    @Throws(MessagingException::class)
    fun setServerCategories(folderServerId: String, messageServerIds: List<String>, categories: List<String>) {
        throw UnsupportedOperationException("This backend does not keep categories on the server")
    }

    @Throws(MessagingException::class)
    fun markAllAsRead(folderServerId: String)

    @Throws(MessagingException::class)
    fun expunge(folderServerId: String)

    @Throws(MessagingException::class)
    fun deleteMessages(folderServerId: String, messageServerIds: List<String>)

    @Throws(MessagingException::class)
    fun deleteAllMessages(folderServerId: String)

    @Throws(MessagingException::class)
    fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>?

    @Throws(MessagingException::class)
    fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>?

    @Throws(MessagingException::class)
    fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>?

    @Throws(MessagingException::class)
    fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String>

    /**
     * Searches every folder at once, for a server that can.
     *
     * @return the matches by folder server id, or `null` when this server can only be searched one folder at a
     *   time with [search] - which is what the app then does.
     */
    @Throws(MessagingException::class)
    fun searchAllFolders(
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): Map<String, List<String>>? = null

    /**
     * Searches every folder at once for mail from one sender, for a server that can tell the sender apart from the
     * other addresses on a message.
     *
     * @return the matches by folder server id, or `null` when this server cannot - the app then searches for the
     *   address as text, which also finds mail sent to it, and leaves those out itself.
     */
    @Throws(MessagingException::class)
    fun searchAllFoldersFromSender(address: String): Map<String, List<String>>? = null

    @Throws(MessagingException::class)
    fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory)

    @Throws(MessagingException::class)
    fun findByMessageId(folderServerId: String, messageId: String): String?

    @Throws(MessagingException::class)
    fun uploadMessage(folderServerId: String, message: Message): String?

    @Throws(MessagingException::class)
    fun sendMessage(message: Message)

    fun createPusher(callback: BackendPusherCallback): BackendPusher
}
