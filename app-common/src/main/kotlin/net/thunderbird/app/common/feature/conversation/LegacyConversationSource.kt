package net.thunderbird.app.common.feature.conversation

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.activity.MessageCompose
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.activity.compose.MessageActions
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.MessageViewInfoExtractorFactory
import com.fsck.k9.ui.messagelist.MessageListConfig
import com.fsck.k9.ui.messagelist.MessageListItem
import com.fsck.k9.ui.messagelist.MessageListLoader
import com.fsck.k9.view.LinkConfirmationActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.thunderbird.app.common.feature.mailscan.loadStoredMessage
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.conversation.ConversationMessage
import net.thunderbird.feature.conversation.ConversationSource
import net.thunderbird.feature.conversation.MessageBody
import net.thunderbird.feature.mail.message.reader.api.html.MessageReaderHtmlSettingsProvider
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.legacy.logging.Log

private const val TAG = "Conversation"
private const val KEY_SEPARATOR = "|"
private const val KEY_PARTS = 4
private const val SERVER_ID_PART = 3

/**
 * Reads a thread from the account's store, as the message list reads it, and shows each message's text as the
 * message view would, from the same extractor and HTML settings.
 *
 * A message known only by its envelope, as Microsoft 365 mail is until it is opened, is downloaded when its text is
 * asked for.
 */
@Suppress("LongParameterList")
internal class LegacyConversationSource(
    private val context: Context,
    private val messageListLoader: MessageListLoader,
    private val localStoreProvider: LocalStoreProvider,
    private val messagingController: MessagingController,
    private val extractorFactory: MessageViewInfoExtractorFactory,
    private val htmlSettingsProvider: MessageReaderHtmlSettingsProvider,
) : ConversationSource {

    override suspend fun messages(accountId: String, threadRoot: Long): List<ConversationMessage> =
        withContext(Dispatchers.IO) {
            val config = MessageListConfig(
                search = threadSearch(accountId, threadRoot),
                showingThreadedList = false,
                sortType = SortType.SORT_DATE,
                sortAscending = true,
                sortDateAscending = true,
                activeMessage = null,
                sortOverrides = emptyMap(),
            )
            messageListLoader.getMessageList(config)?.messageListItems.orEmpty()
                .sortedBy { it.messageDate }
                .map { it.toConversationMessage() }
        }

    override suspend fun body(key: String): MessageBody {
        val message = referenceOf(key)?.let { reference -> loadDownloaded(reference) } ?: return MessageBody.Failed

        return withContext(Dispatchers.Default) {
            try {
                val extractor = extractorFactory.create(htmlSettingsProvider.create())
                val info = extractor.extractMessageForView(message, null, false)
                MessageBody.Html(info.text.orEmpty())
            } catch (e: MessagingException) {
                Log.w(TAG, e, "Couldn't show a message of a conversation")
                MessageBody.Failed
            }
        }
    }

    /**
     * The stored message with its text, downloading the text first when only the header is here; null when either
     * cannot be had.
     */
    private suspend fun loadDownloaded(reference: MessageReference): LocalMessage? {
        val stored = withContext(Dispatchers.IO) { loadStoredMessage(localStoreProvider, reference) }
        val hasText = stored != null &&
            (stored.isSet(Flag.X_DOWNLOADED_FULL) || stored.isSet(Flag.X_DOWNLOADED_PARTIAL))
        return when {
            stored == null -> null
            hasText -> stored
            download(reference) -> withContext(Dispatchers.IO) { loadStoredMessage(localStoreProvider, reference) }
            else -> null
        }
    }

    override fun markRead(keys: List<String>) {
        keys.mapNotNull { key -> parse(key) }
            .groupBy({ it.first.accountId }, { it.second })
            .forEach { (accountId, ids) -> messagingController.setFlag(accountId, ids, Flag.SEEN, true) }
    }

    override fun reply(key: String, toAll: Boolean) {
        val reference = referenceOf(key) ?: return
        start(MessageActions.getActionReplyIntent(context, reference, toAll, null))
    }

    override fun forward(key: String) {
        val reference = referenceOf(key) ?: return
        start(
            Intent(context, MessageCompose::class.java).apply {
                putExtra(MessageCompose.EXTRA_MESSAGE_REFERENCE, reference.toIdentityString())
                action = MessageCompose.ACTION_FORWARD
            },
        )
    }

    override fun openMessage(key: String) {
        val reference = referenceOf(key) ?: return
        start(MessageHomeActivity.actionDisplayMessageIntent(context, reference))
    }

    override fun openLink(url: String, senderAddress: String?) {
        // The screen asks with the application's context, which cannot show a dialog; the activity shows it instead.
        LinkConfirmationActivity.start(context, Uri.parse(url))
    }

    override fun showAsList(accountId: String, threadRoot: Long) {
        start(
            MessageHomeActivity.intentDisplaySearch(
                context = context,
                search = threadSearch(accountId, threadRoot),
                noThreading = true,
                newTask = true,
                clearTop = false,
            ),
        )
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun download(reference: MessageReference): Boolean = suspendCancellableCoroutine { continuation ->
        messagingController.loadMessageRemote(
            reference.accountId,
            reference.folderId,
            reference.uid,
            object : SimpleMessagingListener() {
                override fun loadMessageRemoteFinished(accountId: AccountId?, folderId: Long, uid: String?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun loadMessageRemoteFailed(
                    accountId: AccountId?,
                    folderId: Long,
                    uid: String?,
                    t: Throwable?,
                ) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }
}

private fun threadSearch(accountId: String, threadRoot: Long) = LocalMessageSearch().apply {
    val id = AccountIdFactory.of(accountId)
    this.id = id
    addAccountId(id)
    and(MessageSearchField.THREAD_ID, threadRoot.toString(), SearchAttribute.EQUALS)
}

private fun MessageListItem.toConversationMessage() = ConversationMessage(
    key = listOf(account.id.toString(), folderId.toString(), databaseId.toString(), messageUid)
        .joinToString(KEY_SEPARATOR),
    senderName = displayName.toString(),
    senderAddress = displayAddress?.address,
    date = messageDate,
    subject = subject,
    preview = previewText,
    isRead = isRead,
    hasAttachments = hasAttachments,
    hasWarning = isPossibleImpersonation || isPlusAddressLeak || senderBlocklistKind == BlocklistKind.THREAT,
)

/**
 * The message a key names, and its database id; the server id goes last, as the one part that may hold the separator.
 */
private fun parse(key: String): Pair<MessageReference, Long>? {
    val parts = key.split(KEY_SEPARATOR, limit = KEY_PARTS)
    if (parts.size != KEY_PARTS) return null

    val accountId = runCatching { AccountIdFactory.of(parts[0]) }.getOrNull()
    val folderId = parts[1].toLongOrNull()
    val databaseId = parts[2].toLongOrNull()
    return if (accountId != null && folderId != null && databaseId != null) {
        MessageReference(accountId, folderId, parts[SERVER_ID_PART]) to databaseId
    } else {
        null
    }
}

private fun referenceOf(key: String): MessageReference? = parse(key)?.first
