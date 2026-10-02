package com.fsck.k9.backend.api

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import java.util.Date
import net.thunderbird.core.common.mail.Flag

// FIXME: add documentation
interface BackendFolder {
    val name: String
    val visibleLimit: Int

    fun getMessageServerIds(): Set<String>
    fun getAllMessagesAndEffectiveDates(): Map<String, Long?>
    fun destroyMessages(messageServerIds: List<String>)
    fun clearAllMessages()
    fun getMoreMessages(): MoreMessages
    fun setMoreMessages(moreMessages: MoreMessages)
    fun setLastChecked(timestamp: Long)
    fun setStatus(status: String?)
    fun isMessagePresent(messageServerId: String): Boolean
    fun getMessageFlags(messageServerId: String): Set<Flag>
    fun setMessageFlag(messageServerId: String, flag: Flag, value: Boolean)

    /**
     * The categories the server keeps on a message, such as the ones Outlook assigns.
     */
    fun getMessageServerCategories(messageServerId: String): List<String>

    /**
     * Replaces the categories stored for a message with the ones the server holds for it now.
     */
    fun setMessageServerCategories(messageServerId: String, categories: List<String>)
    suspend fun saveMessage(message: Message, downloadState: MessageDownloadState)

    /**
     * Stores a message under another server ID, for a server that has changed how it identifies the message.
     *
     * If a message is already stored under [newMessageServerId], that one is kept and the one stored under
     * [messageServerId] is removed: they are the same message, and the folder must not show it twice.
     */
    fun changeMessageServerId(messageServerId: String, newMessageServerId: String)
    fun getOldestMessageDate(): Date?
    fun getFolderExtraString(name: String): String?
    fun setFolderExtraString(name: String, value: String?)
    fun getFolderExtraNumber(name: String): Long?
    fun setFolderExtraNumber(name: String, value: Long)

    enum class MoreMessages {
        UNKNOWN,
        FALSE,
        TRUE,
    }
}
