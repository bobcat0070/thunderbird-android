package net.thunderbird.app.common.feature.spamdigest

import android.app.Notification
import android.content.Context
import com.fsck.k9.notification.BackgroundWorkNotificationController
import net.thunderbird.app.common.R
import net.thunderbird.feature.spamdigest.SpamDigestWorkNotification

/**
 * The app's usual background-work notification, saying what the work is.
 */
internal class LegacySpamDigestWorkNotification(
    private val context: Context,
    private val notificationController: BackgroundWorkNotificationController,
) : SpamDigestWorkNotification {
    override val notificationId: Int
        get() = notificationController.notificationId

    override fun create(): Notification {
        return notificationController.createNotification(context.getString(R.string.spam_digest_work_notification))
    }
}
