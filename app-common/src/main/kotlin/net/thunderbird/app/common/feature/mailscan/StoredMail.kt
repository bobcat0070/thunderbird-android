package net.thunderbird.app.common.feature.mailscan

import android.content.Context
import android.content.Intent
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.search.specialFolderId
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.legacy.logging.Log

private const val TAG = "StoredMail"
private const val KEY_SEPARATOR = "|"
private const val KEY_PARTS = 3

/**
 * Mail whose body is on the device, whole or its first part.
 */
internal const val DOWNLOADED_CONDITION =
    "(messages.flags LIKE '%X_DOWNLOADED_FULL%' OR messages.flags LIKE '%X_DOWNLOADED_PARTIAL%')"

/**
 * Mail known only by its envelope.
 */
internal const val NOT_DOWNLOADED_CONDITION = "(messages.flags IS NULL OR " +
    "(messages.flags NOT LIKE '%X_DOWNLOADED_FULL%' AND messages.flags NOT LIKE '%X_DOWNLOADED_PARTIAL%'))"

private val SKIPPED_FOLDERS = listOf(UnifiedFolderKind.TRASH, UnifiedFolderKind.SPAM)

/**
 * A stored mail's envelope, as much as scanning it needs.
 */
internal class StoredMailHeader(
    val reference: MessageReference,
    val date: Long,
    val senderName: String?,
    val senderAddress: String?,
    val subject: String?,
)

/**
 * A mail's reference as a string a screen can hold and hand back: account, folder and server id.
 */
internal fun MessageReference.toKey(): String = listOf(accountId.toString(), folderId.toString(), uid)
    .joinToString(KEY_SEPARATOR)

internal fun messageReferenceOf(key: String): MessageReference? {
    val (account, folder, uid) = key.split(KEY_SEPARATOR, limit = KEY_PARTS).takeIf { it.size == KEY_PARTS }
        ?: return null
    val accountId = runCatching { AccountIdFactory.of(account) }.getOrNull()
    val folderId = folder.toLongOrNull()
    return if (accountId != null && folderId != null) MessageReference(accountId, folderId, uid) else null
}

/**
 * The mail sent since [since] in every account matching [condition], leaving out the trash and spam, newest first.
 *
 * @param conditionArgs the values for the `?` placeholders in [condition], in order.
 */
internal fun storedMailHeaders(
    accountManager: LegacyAccountManager,
    messageStoreManager: MessageStoreManager,
    since: Long,
    condition: String,
    conditionArgs: List<String> = emptyList(),
): List<StoredMailHeader> {
    return accountManager.findAll().flatMap { account ->
        val skippedIds = SKIPPED_FOLDERS.mapNotNull { account.specialFolderId(it) }
        val folderCondition = if (skippedIds.isEmpty()) {
            ""
        } else {
            " AND messages.folder_id NOT IN (${skippedIds.joinToString(",") { "?" }})"
        }

        messageStoreManager.getMessageStore(account.id).getMessages(
            selection = "messages.date >= ? AND $condition$folderCondition",
            selectionArgs = (listOf(since.toString()) + conditionArgs + skippedIds.map { it.toString() })
                .toTypedArray(),
            sortOrder = "messages.date DESC",
            messageMapper = { message ->
                val from = message.fromAddresses.firstOrNull()
                StoredMailHeader(
                    reference = MessageReference(account.id, message.folderId, message.messageServerId),
                    date = message.messageDate,
                    senderName = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address,
                    senderAddress = from?.address,
                    subject = message.subject,
                )
            },
        )
    }.sortedByDescending { it.date }
}

/**
 * Loads a stored mail with its body, or `null` when it is gone.
 */
internal fun loadStoredMessage(localStoreProvider: LocalStoreProvider, reference: MessageReference): LocalMessage? {
    return try {
        val folder = localStoreProvider.getInstance(reference.accountId).getFolder(reference.folderId)
        folder.open()
        val message = folder.getMessage(reference.uid) ?: return null
        folder.fetch(listOf(message), FetchProfile().apply { add(FetchProfile.Item.BODY) }, null)
        message
    } catch (e: MessagingException) {
        Log.w(TAG, e, "Couldn't load a stored message")
        null
    }
}

/**
 * Downloads a mail's body from its server.
 *
 * @return whether it was downloaded.
 */
internal suspend fun downloadStoredMessage(
    messagingController: MessagingController,
    reference: MessageReference,
): Boolean = suspendCancellableCoroutine { continuation ->
    messagingController.loadMessageRemote(
        reference.accountId,
        reference.folderId,
        reference.uid,
        object : SimpleMessagingListener() {
            override fun loadMessageRemoteFinished(accountId: AccountId?, folderId: Long, uid: String?) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun loadMessageRemoteFailed(accountId: AccountId?, folderId: Long, uid: String?, t: Throwable?) {
                if (continuation.isActive) continuation.resume(false)
            }
        },
    )
}

internal fun openStoredMessage(context: Context, key: String) {
    val reference = messageReferenceOf(key) ?: return
    val intent = MessageHomeActivity.actionDisplayMessageIntent(context, reference)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/**
 * An SQL condition that a column contains any of [words], ignoring ASCII case, as SQLite's `LIKE` does.
 */
internal fun containsAny(column: String, words: List<String>): String =
    words.joinToString(" OR ", prefix = "(", postfix = ")") { word ->
        "$column LIKE '%${word.replace("'", "''")}%'"
    }
