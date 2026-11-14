package net.thunderbird.app.common.feature.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mailstore.AttachmentViewInfo
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.message.extractors.AttachmentInfoExtractor
import com.fsck.k9.search.specialFolderId
import com.fsck.k9.ui.messageview.StoredAttachmentViewer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.thunderbird.app.common.feature.newsletter.NAME_SEPARATOR
import net.thunderbird.app.common.feature.newsletter.SENDER_ADDRESS_CONDITION
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.attachments.OpenOutcome
import net.thunderbird.feature.attachments.SenderAttachmentRepository
import net.thunderbird.feature.attachments.SenderAttachments
import net.thunderbird.feature.attachments.StoredAttachment
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.legacy.logging.Log

private const val TAG = "SenderAttachments"

/**
 * How many of a sender's messages with files are read for the list, newest first. Each is loaded with its structure,
 * so a sender who has sent thousands is cut off rather than slowing the screen down.
 */
private const val MAX_MESSAGES = 200

private const val HAS_ATTACHMENTS_CONDITION = " AND messages.attachment_count > 0"

/**
 * Mail known only by its envelope: neither the whole message nor its first part has been downloaded.
 */
private const val NOT_DOWNLOADED_CONDITION = " AND (messages.flags IS NULL OR " +
    "(messages.flags NOT LIKE '%X_DOWNLOADED_FULL%' AND messages.flags NOT LIKE '%X_DOWNLOADED_PARTIAL%'))"

/**
 * Where a listed file is: its message, so it can be loaded again to open the file or show the message.
 */
private class AttachmentLocation(val reference: MessageReference)

private class SenderMessage(val reference: MessageReference, val date: Long, val subject: String?)

/**
 * Lists, opens and downloads one sender's files from the stores of every account.
 *
 * Mail in the trash and in spam is left out, as in the list of all mail from the sender. A file is listed once its
 * message's structure is on the device, which for IMAP is when it is synchronized and for Microsoft 365 when it is
 * opened or downloaded from this screen; a file whose content is still on the server is downloaded when opened.
 */
@Suppress("LongParameterList")
internal class LegacySenderAttachmentRepository(
    private val context: Context,
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
    private val localStoreProvider: LocalStoreProvider,
    private val messagingController: MessagingController,
    private val attachmentInfoExtractor: AttachmentInfoExtractor,
    private val attachmentViewer: StoredAttachmentViewer,
) : SenderAttachmentRepository {

    /**
     * The messages of the files last listed, by file key, for opening them.
     */
    private val locations = ConcurrentHashMap<String, AttachmentLocation>()

    override suspend fun attachments(address: String): SenderAttachments = withContext(Dispatchers.IO) {
        val messages = senderMessages(address, HAS_ATTACHMENTS_CONDITION).take(MAX_MESSAGES)
        val attachments = messages.flatMap { senderMessage ->
            val message = loadMessage(senderMessage.reference) ?: return@flatMap emptyList()
            attachmentsOf(message).mapNotNull { info -> storedAttachment(senderMessage, info) }
        }

        SenderAttachments(
            attachments = attachments,
            messagesNotDownloaded = senderMessages(address, NOT_DOWNLOADED_CONDITION).size,
        )
    }

    override suspend fun downloadMessages(
        address: String,
        limit: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        val messages = withContext(Dispatchers.IO) {
            senderMessages(address, NOT_DOWNLOADED_CONDITION).take(limit)
        }

        var downloaded = 0
        messages.forEachIndexed { index, senderMessage ->
            if (downloadMessage(senderMessage.reference)) downloaded++
            onProgress(index + 1, messages.size)
        }
        return downloaded
    }

    override suspend fun open(attachment: StoredAttachment): OpenOutcome {
        val intent = viewIntentFor(attachment) ?: return OpenOutcome.FAILED

        return try {
            context.startActivity(intent)
            OpenOutcome.OPENED
        } catch (_: ActivityNotFoundException) {
            OpenOutcome.NO_VIEWER
        }
    }

    /**
     * The intent that shows [attachment], once its content is on the device - downloaded first when it is not.
     */
    @Suppress("ReturnCount")
    private suspend fun viewIntentFor(attachment: StoredAttachment): Intent? = withContext(Dispatchers.IO) {
        val location = locations[attachment.key] ?: return@withContext null
        val message = loadMessage(location.reference) ?: return@withContext null
        val info = attachmentsOf(message).firstOrNull { it.internalUri.toString() == attachment.key }
            ?: return@withContext null
        val part = info.part ?: return@withContext null

        if (!info.isContentAvailable && !downloadAttachment(message, part)) return@withContext null
        attachmentViewer.createViewIntent(info)
    }

    override fun showMessage(attachment: StoredAttachment) {
        val location = locations[attachment.key] ?: return
        val intent = MessageHomeActivity.actionDisplayMessageIntent(context, location.reference)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun storedAttachment(senderMessage: SenderMessage, info: AttachmentViewInfo): StoredAttachment? {
        val fileName = info.displayName?.takeIf { it.isNotBlank() } ?: return null
        val key = info.internalUri.toString()
        locations[key] = AttachmentLocation(senderMessage.reference)

        return StoredAttachment(
            key = key,
            fileName = fileName,
            mimeType = info.mimeType,
            size = info.size.takeIf { it != AttachmentViewInfo.UNKNOWN_SIZE },
            date = senderMessage.date,
            subject = senderMessage.subject,
            isDownloaded = info.isContentAvailable,
        )
    }

    /**
     * The sender's messages in every account matching [condition], newest first.
     */
    private fun senderMessages(address: String, condition: String): List<SenderMessage> {
        val senderArgument = address.trim().lowercase() + NAME_SEPARATOR

        return accountManager.findAll().flatMap { account ->
            val excludedIds = EXCLUDED_FOLDERS.mapNotNull { account.specialFolderId(it) }
            val folderCondition = if (excludedIds.isEmpty()) {
                ""
            } else {
                " AND messages.folder_id NOT IN (${excludedIds.joinToString(",") { "?" }})"
            }

            messageStoreManager.getMessageStore(account.id).getMessages(
                selection = SENDER_ADDRESS_CONDITION + condition + folderCondition,
                selectionArgs = (listOf(senderArgument) + excludedIds.map { it.toString() }).toTypedArray(),
                sortOrder = "messages.date DESC",
                messageMapper = { message ->
                    SenderMessage(
                        reference = MessageReference(account.id, message.folderId, message.messageServerId),
                        date = message.messageDate,
                        subject = message.subject,
                    )
                },
            )
        }.sortedByDescending { it.date }
    }

    private fun loadMessage(reference: MessageReference): LocalMessage? {
        return try {
            val folder = localStoreProvider.getInstance(reference.accountId).getFolder(reference.folderId)
            folder.open()
            val message = folder.getMessage(reference.uid) ?: return null
            val fetchProfile = FetchProfile().apply { add(FetchProfile.Item.BODY) }
            folder.fetch(listOf(message), fetchProfile, null)
            message
        } catch (e: MessagingException) {
            Log.w(TAG, e, "Couldn't load a message to list its attachments")
            null
        }
    }

    /**
     * The message's files, leaving out the pictures its text shows in place.
     */
    private fun attachmentsOf(message: Message): List<AttachmentViewInfo> {
        val parts = mutableListOf<Part>()
        MessageExtractor.findViewablesAndAttachments(message, null, parts)
        return attachmentInfoExtractor.extractAttachmentInfoForView(parts).filterNot { it.inlineAttachment }
    }

    private suspend fun downloadAttachment(message: LocalMessage, part: Part): Boolean {
        val accountId = message.accountId ?: return false
        return suspendCancellableCoroutine { continuation ->
            messagingController.loadAttachment(
                accountId,
                message,
                part,
                object : SimpleMessagingListener() {
                    override fun loadAttachmentFinished(accountId: AccountId?, message: Message?, part: Part?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun loadAttachmentFailed(
                        accountId: AccountId?,
                        message: Message?,
                        part: Part?,
                        reason: String?,
                    ) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
        }
    }

    private suspend fun downloadMessage(reference: MessageReference): Boolean {
        return suspendCancellableCoroutine { continuation ->
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

    private companion object {
        val EXCLUDED_FOLDERS = listOf(UnifiedFolderKind.TRASH, UnifiedFolderKind.SPAM)
    }
}
