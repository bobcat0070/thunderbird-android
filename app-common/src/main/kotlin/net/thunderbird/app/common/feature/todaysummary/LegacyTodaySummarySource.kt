package net.thunderbird.app.common.feature.todaysummary

import android.content.Context
import android.content.Intent
import app.k9mail.feature.launcher.FeatureLauncherActivity
import app.k9mail.feature.launcher.FeatureLauncherTarget
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.message.extractors.findVerificationCode
import com.fsck.k9.message.extractors.looksLikeSignInMail
import com.fsck.k9.search.specialFolderId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.packages.ParcelsArrivingToday
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.feature.todaysummary.TodaySummary
import net.thunderbird.feature.todaysummary.TodaySummaryDestinations
import net.thunderbird.feature.todaysummary.TodaySummarySource

/**
 * How far back unread mail from people counts as waiting: older than this, it has been put off on purpose.
 */
private val WAITING_WINDOW = 14.days

/**
 * Folders whose mail is not news for the morning: dealt with, or the reader's own.
 */
private val SETTLED_FOLDERS = listOf(
    UnifiedFolderKind.ARCHIVE,
    UnifiedFolderKind.TRASH,
    UnifiedFolderKind.SPAM,
    UnifiedFolderKind.SENT,
    UnifiedFolderKind.DRAFTS,
)

private class Row(val sender: String?, val senderAddress: String?, val subject: String?, val preview: String?)

/**
 * Whether [senderAddress] is one of [ownAddresses] (lowercase): mail the reader sent themselves, from this account or
 * another of theirs, which no one is waiting on.
 */
internal fun isFromReader(senderAddress: String?, ownAddresses: Set<String>): Boolean =
    senderAddress != null && senderAddress.lowercase() in ownAddresses

/**
 * Reads the summary of the day from every account's store and from Packages.
 *
 * People waiting are unread personal mail in an inbox from the last two weeks, not sent by the reader; codes are
 * sign-in and verification mail, found as the notifications that offer a code to copy find them, since the evening
 * before.
 */
internal class LegacyTodaySummarySource(
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
    private val generalSettingsManager: GeneralSettingsManager,
    private val parcelsArrivingToday: ParcelsArrivingToday,
    private val clock: Clock,
) : TodaySummarySource {

    override suspend fun summary(startOfToday: Long, overnightSince: Long): TodaySummary {
        val parcels = if (generalSettingsManager.getConfig().interaction.isPackagesEnabled) {
            parcelsArrivingToday.arrivingToday(startOfToday)
        } else {
            emptyList()
        }

        return withContext(Dispatchers.IO) {
            val waitingSince = (clock.now() - WAITING_WINDOW).toEpochMilliseconds()
            val accounts = accountManager.findAll()
            val ownAddresses = accounts.flatMap { account -> account.identities }
                .mapNotNull { identity -> identity.email?.lowercase() }
                .toSet()
            val waiting = accounts.flatMap { account -> waitingRows(account, waitingSince) }
                .filterNot { isFromReader(it.senderAddress, ownAddresses) }
            val codes = accounts.flatMap { account -> recentRows(account, overnightSince) }
                .filter { looksLikeSignInMail(it.subject) || findVerificationCode(it.subject, it.preview) != null }

            TodaySummary(
                parcels = parcels,
                waitingCount = waiting.size,
                waitingSenders = waiting.mapNotNull { it.sender }.distinct(),
                codeCount = codes.size,
                codeSenders = codes.mapNotNull { it.sender }.distinct(),
            )
        }
    }

    private fun waitingRows(account: LegacyAccount, since: Long): List<Row> {
        val inbox = account.inboxFolderId ?: return emptyList()
        return rows(
            account = account,
            selection = "messages.folder_id = ? AND messages.read = 0 AND messages.classification = ? AND " +
                "messages.date >= ?",
            arguments = listOf(inbox.toString(), MessageClass.HUMAN.name, since.toString()),
        )
    }

    private fun recentRows(account: LegacyAccount, since: Long): List<Row> {
        val settled = SETTLED_FOLDERS.mapNotNull { account.specialFolderId(it) }
        val folderCondition = if (settled.isEmpty()) {
            ""
        } else {
            " AND messages.folder_id NOT IN (${settled.joinToString(",") { "?" }})"
        }
        return rows(
            account = account,
            selection = "messages.date >= ? AND " +
                "(messages.classification IS NULL OR messages.classification != ?)$folderCondition",
            arguments = listOf(since.toString(), MessageClass.NEWSLETTER.name) + settled.map { it.toString() },
        )
    }

    private fun rows(account: LegacyAccount, selection: String, arguments: List<String>): List<Row> {
        return messageStoreManager.getMessageStore(account.id).getMessages(
            selection = selection,
            selectionArgs = arguments.toTypedArray(),
            sortOrder = "messages.date DESC",
            messageMapper = { message ->
                val from = message.fromAddresses.firstOrNull()
                Row(
                    sender = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address,
                    senderAddress = from?.address,
                    subject = message.subject,
                    preview = message.preview.takeIf { it.isPreviewTextAvailable }?.previewText,
                )
            },
        )
    }
}

internal class LegacyTodaySummaryDestinations(private val context: Context) : TodaySummaryDestinations {
    override fun inbox(): Intent = Intent(context, MessageHomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    override fun packages(): Intent = FeatureLauncherActivity.getIntent(context, FeatureLauncherTarget.Packages)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
