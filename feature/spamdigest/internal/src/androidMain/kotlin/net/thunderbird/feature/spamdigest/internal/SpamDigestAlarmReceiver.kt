package net.thunderbird.feature.spamdigest.internal

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
 * Puts the digest's alarm back after the phone restarts, which clears every alarm. Switched on only while the digest
 * is, so the app is not started at boot for nothing.
 */
class SpamDigestBootReceiver : BroadcastReceiver(), KoinComponent {
    private val scheduler: SpamDigestScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            scheduler.ensureScheduled()
        }
    }
}
