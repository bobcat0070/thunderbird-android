package net.thunderbird.core.preference.notification

import net.thunderbird.core.common.notification.NotificationActionTokens
import net.thunderbird.core.preference.LockScreenNotificationVisibility
import net.thunderbird.core.preference.NotificationQuickDelete

const val NOTIFICATION_PREFERENCE_DEFAULT_IS_QUIET_TIME_ENABLED = false
const val NOTIFICATION_PREFERENCE_DEFAULT_QUIET_TIME_STARTS = "21:00"
const val NOTIFICATION_PREFERENCE_DEFAULT_QUIET_TIME_END = "7:00"
const val NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFICATION_DURING_QUIET_TIME_ENABLED = true
val NOTIFICATION_PREFERENCE_DEFAULT_MESSAGE_ACTIONS_ORDER = NotificationActionTokens.DEFAULT_ORDER
const val NOTIFICATION_PREFERENCE_DEFAULT_MESSAGE_ACTIONS_CUTOFF = 3
const val NOTIFICATION_PREFERENCE_MAX_MESSAGE_ACTIONS_SHOWN = 3
const val NOTIFICATION_PREFERENCE_DEFAULT_IS_SUMMARY_DELETE_ACTION_ENABLED = true
const val NOTIFICATION_PREFERENCE_DEFAULT_IS_SHOW_CONTACT_PICTURE_IN_NOTIFICATION = true
val NOTIFICATION_PREFERENCE_DEFAULT_QUICK_DELETE_BEHAVIOUR = NotificationQuickDelete.ALWAYS
val NOTIFICATION_PREFERENCE_DEFAULT_LOCK_SCREEN_NOTIFICATION_VISIBILITY = LockScreenNotificationVisibility.MESSAGE_COUNT
const val NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFY_PERSONAL = true
const val NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFY_NOTIFICATIONS = true
const val NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFY_NEWSLETTERS = true

data class NotificationPreference(
    val isQuietTimeEnabled: Boolean = NOTIFICATION_PREFERENCE_DEFAULT_IS_QUIET_TIME_ENABLED,
    val quietTimeStarts: String = NOTIFICATION_PREFERENCE_DEFAULT_QUIET_TIME_STARTS,
    val quietTimeEnds: String = NOTIFICATION_PREFERENCE_DEFAULT_QUIET_TIME_END,
    val isNotificationDuringQuietTimeEnabled: Boolean =
        NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFICATION_DURING_QUIET_TIME_ENABLED,
    val messageActionsOrder: List<String> = NOTIFICATION_PREFERENCE_DEFAULT_MESSAGE_ACTIONS_ORDER,
    val messageActionsCutoff: Int = NOTIFICATION_PREFERENCE_DEFAULT_MESSAGE_ACTIONS_CUTOFF,
    val isSummaryDeleteActionEnabled: Boolean = NOTIFICATION_PREFERENCE_DEFAULT_IS_SUMMARY_DELETE_ACTION_ENABLED,
    val isShowContactPictureInNotification: Boolean =
        NOTIFICATION_PREFERENCE_DEFAULT_IS_SHOW_CONTACT_PICTURE_IN_NOTIFICATION,
    val notificationQuickDeleteBehaviour: NotificationQuickDelete =
        NOTIFICATION_PREFERENCE_DEFAULT_QUICK_DELETE_BEHAVIOUR,
    val lockScreenNotificationVisibility: LockScreenNotificationVisibility =
        NOTIFICATION_PREFERENCE_DEFAULT_LOCK_SCREEN_NOTIFICATION_VISIBILITY,

    /**
     * Whether mail that was not identified as bulk raises a notification. Covers mail the classifier could
     * not place, because silencing a message we could not identify is the failure that actually costs
     * something.
     */
    val isNotifyPersonal: Boolean = NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFY_PERSONAL,
    val isNotifyNotifications: Boolean = NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFY_NOTIFICATIONS,
    val isNotifyNewsletters: Boolean = NOTIFICATION_PREFERENCE_DEFAULT_IS_NOTIFY_NEWSLETTERS,
)
