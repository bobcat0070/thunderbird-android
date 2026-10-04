package net.thunderbird.feature.spamdigest.internal

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives the digest's alarm.
 */
class SpamDigestAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: AlarmSpamDigestScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RUN) scheduler.onAlarm()
    }

    internal companion object {
        const val ACTION_RUN = "net.thunderbird.feature.spamdigest.RUN"
    }
}

/**
 * Puts the digest's alarm back after the phone restarts, which clears every alarm, and sets it again as an exact one
 * once the reader allows exact alarms. Switched on only while the digest is, so the app is not started for nothing.
 */
class SpamDigestBootReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: SpamDigestScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in RESCHEDULE_ACTIONS) scheduler.ensureScheduled()
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
