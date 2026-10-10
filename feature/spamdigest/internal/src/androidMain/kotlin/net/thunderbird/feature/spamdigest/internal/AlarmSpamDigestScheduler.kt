package net.thunderbird.feature.spamdigest.internal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.getSystemService
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import net.thunderbird.feature.spamdigest.SpamDigestTime

private const val LOG_TAG = "SpamDigest"
private const val UNIQUE_WORK_NAME = "SpamDigestRun"

/**
 * The name the digest's work went by when each run scheduled the next as delayed work. A phone updated from that
 * build still has one waiting, which would hold the alarm's run back behind it.
 */
private const val CHAINED_WORK_NAME = "SpamDigest"
private const val INITIAL_BACKOFF_MINUTES = 15L
private const val ALARM_REQUEST_CODE = 0

/**
 * Schedules each digest with an alarm at the chosen time, which starts the digest as urgent background work.
 *
 * Not a delayed background job: Android 16 holds those back until the phone is charging or idle, which turned a
 * 7:00 digest into a mid-morning one. An alarm goes off at the time - exactly when the app may set exact alarms, as
 * push already asks it to, and otherwise within the few minutes Android allows - and urgent work then runs at once.
 *
 * Alarms do not survive a restart of the phone, so a boot receiver is switched on while the digest is. Nor is an
 * alarm or the work it starts guaranteed to run - a digest went missing every few days - so a digest that is overdue
 * is sent the next time the app starts or syncs mail, and each digest covers every day since the last one sent.
 */
internal class AlarmSpamDigestScheduler(
    private val context: Context,
    private val workManager: WorkManager,
    private val settingsStore: SpamDigestSettingsStore,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val logger: Logger,
) : SpamDigestScheduler {

    private val alarmManager: AlarmManager = requireNotNull(context.getSystemService())

    override fun reschedule() {
        schedule(catchUp = false)
    }

    override fun ensureScheduled() {
        workManager.cancelUniqueWork(CHAINED_WORK_NAME)
        schedule(catchUp = true)
    }

    override fun catchUpIfDue() {
        val settings = settingsStore.getSettings()
        if (!settings.isEnabled || !isOverdue(settings.sendTime)) return

        logger.info(LOG_TAG) { "A spam digest is overdue; sending it now" }
        runNow()
    }

    /**
     * The alarm went off: send the digest now, and set tomorrow's alarm straight away so a failed digest cannot
     * stop the ones after it.
     */
    fun onAlarm() {
        logger.info(LOG_TAG) { "Spam digest alarm went off" }
        runNow()
        schedule(catchUp = false)
    }

    private fun isOverdue(sendTime: SpamDigestTime): Boolean {
        return isDigestOverdue(clock.now(), timeZoneProvider.current(), sendTime, settingsStore.lastSentDay())
    }

    private fun schedule(catchUp: Boolean) {
        val settings = settingsStore.getSettings()
        if (!settings.isEnabled) {
            alarmManager.cancel(alarmIntent())
            settingsStore.setScheduledFor(null)
            setBootReceiverEnabled(false)
            return
        }

        setBootReceiverEnabled(true)

        if (catchUp && isOverdue(settings.sendTime)) {
            logger.info(LOG_TAG) { "A spam digest is overdue; sending it now" }
            runNow()
        }

        val runAt = nextRunAt(clock.now(), timeZoneProvider.current(), settings.sendTime)
        setAlarm(runAt)
        settingsStore.setScheduledFor(runAt)
    }

    private fun setAlarm(runAt: Instant) {
        val triggerAt = runAt.toEpochMilliseconds()
        if (AlarmManagerCompat.canScheduleExactAlarms(alarmManager)) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager,
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                alarmIntent(),
            )
        } else {
            AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent())
        }
    }

    private fun runNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SpamDigestWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        // A run already under way is left to finish. One still waiting - held back by Android, perhaps since an
        // earlier day - is replaced, so it cannot keep this one from running; each run reports on every day due
        // anyway, and the record of sent days stops a day being reported twice.
        val isRunning = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING }
        val policy = if (isRunning) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    private fun alarmIntent(): PendingIntent {
        val intent = Intent(context, SpamDigestAlarmReceiver::class.java).setAction(SpamDigestAlarmReceiver.ACTION_RUN)

        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setBootReceiverEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, SpamDigestBootReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
