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
 * Posted under a tag of its own, so its ids cannot collide with the fixed per-account ranges the other notifications
 * are numbered in.
 */
private const val NOTIFICATION_TAG = "spam_alert"

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
        if (!canPostNotifications()) return

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

        try {
            notificationHelper.getNotificationManager()
                .notify(NOTIFICATION_TAG, "$accountId/$folderId/$messageServerId".hashCode(), notification)
        } catch (e: SecurityException) {
            logger.warn(LOG_TAG) { "Could not post a spam alert: ${e::class.simpleName}" }
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun String.oneLine(): String {
        val line = filterNot { it.category == CharCategory.FORMAT }.replace(Regex("""\s+"""), " ").trim()
        return if (line.length > MAX_TEXT_LENGTH) line.take(MAX_TEXT_LENGTH).trimEnd() + "…" else line
    }
}
