package net.thunderbird.app.common.feature.spamdigest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.notification.NotificationActionCreator
import com.fsck.k9.notification.NotificationChannelManager.ChannelType
import com.fsck.k9.notification.NotificationHelper
import com.fsck.k9.notification.NotificationResourceProvider
import net.thunderbird.app.common.R
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamAlertNotifier
import net.thunderbird.feature.spamdigest.SpamMessage

private const val LOG_TAG = "SpamAlert"

/**
 * Spam alerts are numbered in a range of their own, far above the small per-account ranges the other notifications
 * use, with the low bits taken from the message so each message gets one notification.
 */
private const val NOTIFICATION_ID_BASE = 0x5A000000
private const val NOTIFICATION_ID_MASK = 0x00FFFFFF

/**
 * Lines a sender wrote are shown on one line, at most this long.
 */
private const val MAX_TEXT_LENGTH = 120

/**
 * Tells the reader that mail from someone they know landed in spam, and opens that message when tapped.
 *
 * On the lock screen it says only that much, without the sender or subject, like the app's other notifications.
 */
internal class LegacySpamAlertNotifier(
    private val context: Context,
    private val accountManager: LegacyAccountDtoManager,
    private val notificationHelper: NotificationHelper,
    private val actionCreator: NotificationActionCreator,
    private val resourceProvider: NotificationResourceProvider,
    private val logger: Logger,
) : SpamAlertNotifier {

    override fun notify(accountId: String, folderId: Long, messageServerId: String, message: SpamMessage) {
        val account = accountManager.getAccount(accountId) ?: return
        // Checked here, beside the call that needs it, where lint can see it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = context.getString(R.string.spam_alert_title)
        val sender = (message.senderName?.takeIf { it.isNotBlank() } ?: message.senderAddress).orEmpty().oneLine()
        val subject = message.subject.orEmpty().oneLine()
        val text = context.getString(R.string.spam_alert_text, sender, subject)

        val pendingIntent = actionCreator.createViewMessagePendingIntent(
            MessageReference(accountId, folderId, messageServerId),
        )

        val publicVersion = notificationHelper.createNotificationBuilder(account, ChannelType.MISCELLANEOUS)
            .setSmallIcon(resourceProvider.iconWarning)
            .setColor(account.chipColor)
            .setContentTitle(title)
            .setSubText(account.displayName)
            .build()

        val notification = notificationHelper.createNotificationBuilder(account, ChannelType.MISCELLANEOUS)
            .setSmallIcon(resourceProvider.iconWarning)
            .setColor(account.chipColor)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(account.displayName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        // Posted through the helper the app's other notifications use, which handles a refused notification.
        val notificationId =
            NOTIFICATION_ID_BASE + ("$accountId/$folderId/$messageServerId".hashCode() and NOTIFICATION_ID_MASK)
        notificationHelper.notify(account, notificationId, notification)
        logger.debug(LOG_TAG) { "Posted a spam alert" }
    }

    private fun String.oneLine(): String {
        val line = filterNot { it.category == CharCategory.FORMAT }.replace(Regex("""\s+"""), " ").trim()
        return if (line.length > MAX_TEXT_LENGTH) line.take(MAX_TEXT_LENGTH).trimEnd() + "…" else line
    }
}
