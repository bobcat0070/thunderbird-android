package net.thunderbird.feature.spamdigest

import android.app.Notification

/**
 * The notification shown while a digest is being sent, on Android versions that run urgent background work as a
 * foreground service. Provided by the app, so it uses the app's own channel for background work.
 */
public interface SpamDigestWorkNotification {
    public val notificationId: Int

    public fun create(): Notification
}
