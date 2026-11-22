package net.thunderbird.app.common.feature.expiredmail

import android.content.Context
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MessagingControllerWrapper
import com.fsck.k9.message.extractors.findVerificationCode
import com.fsck.k9.message.extractors.looksLikeSignInMail
import com.fsck.k9.search.specialFolderId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.app.common.feature.mailscan.messageReferenceOf
import net.thunderbird.app.common.feature.mailscan.openStoredMessage
import net.thunderbird.app.common.feature.mailscan.toKey
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.expiredmail.ExpiredKind
import net.thunderbird.feature.expiredmail.ExpiredMail
import net.thunderbird.feature.expiredmail.ExpiredMailSource
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.packages.DeliveredParcelMail
import net.thunderbird.feature.search.legacy.UnifiedFolderKind

/**
 * How old a sign-in or verification mail has to be before its code or link has surely run out.
 */
private val SIGN_IN_EXPIRY = 1.days

/**
 * How long after a parcel's last mail its shipping mail is of no more use.
 */
private val DELIVERED_EXPIRY = 14.days

/**
 * How far back sign-in mail is looked for. Older mail is still found by the next look, a year on from its own date.
 */
private val SIGN_IN_SCAN = 365.days

/**
 * Folders whose mail is never listed: already dealt with, or the reader's own.
 */
private val SETTLED_FOLDERS = listOf(
    UnifiedFolderKind.ARCHIVE,
    UnifiedFolderKind.TRASH,
    UnifiedFolderKind.SPAM,
    UnifiedFolderKind.SENT,
    UnifiedFolderKind.DRAFTS,
)

private class StoredRow(
    val reference: MessageReference,
    val date: Long,
    val senderName: String?,
    val subject: String?,
    val preview: String?,
)

/**
 * Finds expired mail in the stores of every account: sign-in and verification mail, by the same reading of subject
 * and preview that offers a code to copy or a link to open, and the shipping mail Packages finds delivered.
 * Newsletters are never taken for sign-in mail, whatever their subject.
 */
internal class LegacyExpiredMailSource(
    private val context: Context,
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
    private val messagingController: MessagingControllerWrapper,
    private val deliveredParcelMail: DeliveredParcelMail,
    private val clock: Clock,
) : ExpiredMailSource {

    override suspend fun expiredMail(): List<ExpiredMail> {
        val now = clock.now()
        val signIn = withContext(Dispatchers.IO) {
            signInMail(
                since = (now - SIGN_IN_SCAN).toEpochMilliseconds(),
                before = (now - SIGN_IN_EXPIRY).toEpochMilliseconds(),
            )
        }
        val signInKeys = signIn.mapTo(mutableSetOf()) { it.key }

        val parcelKeys = deliveredParcelMail.deliveredMailKeys((now - DELIVERED_EXPIRY).toEpochMilliseconds())
        val parcels = withContext(Dispatchers.IO) {
            parcelKeys.filterNot { it in signInKeys }.mapNotNull { key -> parcelMail(key) }
        }

        return (signIn + parcels).sortedByDescending { it.date }
    }

    override suspend fun archive(keys: List<String>): Int = withContext(Dispatchers.IO) {
        val accounts = accountManager.findAll().associateBy { it.id }
        val references = keys.mapNotNull(::messageReferenceOf)
            .filter { accounts[it.accountId]?.archiveFolderId != null }
        if (references.isNotEmpty()) messagingController.archiveMessages(references)
        references.size
    }

    override suspend fun delete(keys: List<String>): Int = withContext(Dispatchers.IO) {
        val references = keys.mapNotNull(::messageReferenceOf)
        if (references.isNotEmpty()) messagingController.deleteMessages(references)
        references.size
    }

    override fun openMail(key: String) {
        openStoredMessage(context, key)
    }

    private fun signInMail(since: Long, before: Long): List<ExpiredMail> {
        return accountManager.findAll().flatMap { account ->
            val (folderCondition, folderArgs) = settledFolderCondition(account)
            rows(
                account = account,
                selection = "messages.date >= ? AND messages.date < ? AND " +
                    "(messages.classification IS NULL OR messages.classification != ?)$folderCondition",
                arguments = listOf(since.toString(), before.toString(), MessageClass.NEWSLETTER.name) + folderArgs,
            )
        }
            .filter { row ->
                looksLikeSignInMail(row.subject) || findVerificationCode(row.subject, row.preview) != null
            }
            .map { row -> row.toExpiredMail(ExpiredKind.SIGN_IN) }
    }

    /**
     * The mail [key] names, unless it has gone or has been archived, deleted or otherwise dealt with since.
     */
    @Suppress("ReturnCount")
    private fun parcelMail(key: String): ExpiredMail? {
        val reference = messageReferenceOf(key) ?: return null
        val account = accountManager.findById(reference.accountId) ?: return null
        val (folderCondition, folderArgs) = settledFolderCondition(account)

        return rows(
            account = account,
            selection = "messages.folder_id = ? AND messages.uid = ?$folderCondition",
            arguments = listOf(reference.folderId.toString(), reference.uid) + folderArgs,
            limit = 1,
        ).firstOrNull()?.toExpiredMail(ExpiredKind.DELIVERED_PARCEL)
    }

    private fun settledFolderCondition(account: LegacyAccount): Pair<String, List<String>> {
        val ids = SETTLED_FOLDERS.mapNotNull { account.specialFolderId(it) }
        if (ids.isEmpty()) return "" to emptyList()
        return " AND messages.folder_id NOT IN (${ids.joinToString(",") { "?" }})" to ids.map { it.toString() }
    }

    private fun rows(
        account: LegacyAccount,
        selection: String,
        arguments: List<String>,
        limit: Int? = null,
    ): List<StoredRow> {
        return messageStoreManager.getMessageStore(account.id).getMessages(
            selection = selection,
            selectionArgs = arguments.toTypedArray(),
            sortOrder = "messages.date DESC",
            messageMapper = { message ->
                val from = message.fromAddresses.firstOrNull()
                StoredRow(
                    reference = MessageReference(account.id, message.folderId, message.messageServerId),
                    date = message.messageDate,
                    senderName = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address,
                    subject = message.subject,
                    preview = message.preview.takeIf { it.isPreviewTextAvailable }?.previewText,
                )
            },
            limit = limit,
        )
    }

    private fun StoredRow.toExpiredMail(kind: ExpiredKind) = ExpiredMail(
        key = reference.toKey(),
        kind = kind,
        date = date,
        senderName = senderName,
        subject = subject,
    )
}
