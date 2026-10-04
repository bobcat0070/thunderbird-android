package net.thunderbird.app.common.feature.newsletter

import android.content.Context
import android.content.Intent
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.activity.MessageCompose
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.controller.MessagingControllerWrapper
import com.fsck.k9.helper.HttpsUnsubscribeUri
import com.fsck.k9.helper.MailtoUnsubscribeUri
import com.fsck.k9.mailstore.MessageRepository
import com.fsck.k9.search.specialFolderId
import com.fsck.k9.ui.unsubscribe.OneClickUnsubscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.newsletter.NewsletterActions
import net.thunderbird.feature.newsletter.NewsletterSender
import net.thunderbird.feature.newsletter.NewsletterSenderRepository
import net.thunderbird.feature.newsletter.UnsubscribeOutcome
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.feature.search.legacy.createSenderSearch

/**
 * What the stored address list puts between an address and its display name; see
 * [net.thunderbird.feature.search.legacy.api.MessageSearchField.SENDER_ADDRESS].
 */
private const val NAME_SEPARATOR = ";\u0001"
private const val SENDER_ADDRESS_CONDITION = "instr(lower(messages.sender_list) || ';' || char(1), ?) = 1"

/**
 * One stored message, as much of it as the newsletter screen needs.
 */
private class StoredMessage(
    val reference: MessageReference,
    val address: String,
    val name: String?,
    val date: Long,
)

/**
 * Reads messages from the stores of every account, leaving out the folders given.
 */
private fun MessageStoreManager.messagesOutside(
    accounts: List<LegacyAccountDto>,
    excludedFolders: List<UnifiedFolderKind>,
    condition: String,
    conditionArgs: List<String>,
): List<StoredMessage> {
    return accounts.flatMap { account ->
        val excludedIds = excludedFolders.mapNotNull { account.specialFolderId(it) }
        val folderCondition = if (excludedIds.isEmpty()) {
            ""
        } else {
            " AND messages.folder_id NOT IN (${excludedIds.joinToString(",") { "?" }})"
        }

        getMessageStore(account).getMessages(
            selection = condition + folderCondition,
            selectionArgs = (conditionArgs + excludedIds.map { it.toString() }).toTypedArray(),
            sortOrder = "messages.date DESC",
            messageMapper = { message ->
                message.fromAddresses.firstOrNull()?.takeIf { !it.address.isNullOrBlank() }?.let { from ->
                    StoredMessage(
                        reference = MessageReference(account.uuid, message.folderId, message.messageServerId),
                        address = from.address.lowercase(),
                        name = from.personal?.takeIf { it.isNotBlank() },
                        date = message.messageDate,
                    )
                }
            },
        )
    }
}

/**
 * Newsletter senders from the stored mail of every account.
 *
 * Counted in the folders the reader still has to look at: archived, trashed and spam newsletters are dealt with, and
 * sent or draft mail is the reader's own.
 */
internal class LegacyNewsletterSenderRepository(
    private val accountManager: LegacyAccountDtoManager,
    private val messageStoreManager: MessageStoreManager,
    private val messageRepository: MessageRepository,
) : NewsletterSenderRepository {

    override suspend fun senders(): List<NewsletterSender> = withContext(Dispatchers.IO) {
        val newsletters = messageStoreManager.messagesOutside(
            accounts = accountManager.getAccounts(),
            excludedFolders = HANDLED_FOLDERS,
            condition = "messages.classification = ?",
            conditionArgs = listOf(MessageClass.NEWSLETTER.name),
        )

        newsletters.groupBy { it.address }
            .map { (address, messages) ->
                val newest = messages.maxBy { it.date }
                NewsletterSender(
                    address = address,
                    name = messages.sortedByDescending { it.date }.firstNotNullOfOrNull { it.name },
                    messageCount = messages.size,
                    latestAt = newest.date,
                    canUnsubscribe = runCatching { messageRepository.getUnsubscribeUri(newest.reference) != null }
                        .getOrDefault(false),
                )
            }
            .sortedWith(compareByDescending<NewsletterSender> { it.messageCount }.thenByDescending { it.latestAt })
    }

    private companion object {
        val HANDLED_FOLDERS = listOf(
            UnifiedFolderKind.ARCHIVE,
            UnifiedFolderKind.TRASH,
            UnifiedFolderKind.SPAM,
            UnifiedFolderKind.SENT,
            UnifiedFolderKind.DRAFTS,
        )
    }
}

/**
 * Acts on all of a sender's mail in every account. Mail already in the trash or in spam is never touched: deleting
 * from the trash is permanent, and spam has its own tools.
 */
internal class LegacyNewsletterActions(
    private val context: Context,
    private val accountManager: LegacyAccountDtoManager,
    private val messageStoreManager: MessageStoreManager,
    private val messageRepository: MessageRepository,
    private val messagingController: MessagingControllerWrapper,
    private val oneClickUnsubscriber: OneClickUnsubscriber,
) : NewsletterActions {

    override fun showAllMail(address: String) {
        val intent = MessageHomeActivity.intentDisplaySearch(
            context = context,
            search = createSenderSearch(address),
            noThreading = true,
            newTask = true,
            clearTop = false,
        )
        context.startActivity(intent)
    }

    override suspend fun unsubscribe(address: String): UnsubscribeOutcome {
        val newest = withContext(Dispatchers.IO) {
            mailFrom(address, NOT_HANDLED).maxByOrNull { it.date }?.reference
        } ?: return UnsubscribeOutcome.UNAVAILABLE

        val oneClickUri = withContext(Dispatchers.IO) {
            runCatching { messageRepository.getOneClickUnsubscribeUri(newest) }.getOrNull()
        }
        val isSent = oneClickUri != null && oneClickUnsubscriber.unsubscribe(oneClickUri)

        return if (isSent) UnsubscribeOutcome.SENT else openUnsubscribe(newest)
    }

    override suspend fun archiveAll(address: String): Int = withContext(Dispatchers.IO) {
        val messages = mailFrom(address, NOT_HANDLED + UnifiedFolderKind.ARCHIVE).map { it.reference }
        if (messages.isNotEmpty()) messagingController.archiveMessages(messages)
        messages.size
    }

    override suspend fun deleteAll(address: String): Int = withContext(Dispatchers.IO) {
        val messages = mailFrom(address, NOT_HANDLED).map { it.reference }
        if (messages.isNotEmpty()) messagingController.deleteMessages(messages)
        messages.size
    }

    private fun mailFrom(address: String, excludedFolders: List<UnifiedFolderKind>): List<StoredMessage> {
        return messageStoreManager.messagesOutside(
            accounts = accountManager.getAccounts(),
            excludedFolders = excludedFolders,
            condition = SENDER_ADDRESS_CONDITION,
            conditionArgs = listOf(address.trim().lowercase() + NAME_SEPARATOR),
        )
    }

    /**
     * Opens the sender's own way out, as the message view does: their page, or an unsubscribe message to write.
     */
    private suspend fun openUnsubscribe(messageReference: MessageReference): UnsubscribeOutcome {
        val unsubscribeUri = withContext(Dispatchers.IO) {
            runCatching { messageRepository.getUnsubscribeUri(messageReference) }.getOrNull()
        }

        val intent = when (unsubscribeUri) {
            is MailtoUnsubscribeUri -> Intent(context, MessageCompose::class.java).apply {
                action = Intent.ACTION_VIEW
                data = unsubscribeUri.uri
                putExtra(MessageCompose.EXTRA_ACCOUNT, messageReference.accountUuid)
            }

            is HttpsUnsubscribeUri -> Intent(Intent.ACTION_VIEW, unsubscribeUri.uri)

            else -> return UnsubscribeOutcome.UNAVAILABLE
        }

        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return UnsubscribeOutcome.OPENED_ELSEWHERE
    }

    private companion object {
        val NOT_HANDLED = listOf(UnifiedFolderKind.TRASH, UnifiedFolderKind.SPAM)
    }
}
