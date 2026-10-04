package net.thunderbird.feature.spamdigest.internal

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import net.thunderbird.feature.spamdigest.SpamDigestScheduler

private const val UNIQUE_WORK_NAME = "SpamDigest"
private const val INITIAL_BACKOFF_MINUTES = 15L

/**
 * Schedules each digest as a one-off job that, once done, schedules the next.
 *
 * Not a periodic job: those drift from the chosen time a little more with every day the phone was dozing when
 * one was due, and a digest that creeps from 7:00 to mid-morning over a few weeks is not what was asked for.
 */
internal class WorkManagerSpamDigestScheduler(
    private val workManager: WorkManager,
    private val settingsStore: SpamDigestSettingsStore,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
) : SpamDigestScheduler {

    override fun reschedule() {
        schedule(ExistingWorkPolicy.REPLACE)
    }

    override fun ensureScheduled() {
        schedule(ExistingWorkPolicy.KEEP)
    }

    /**
     * Schedules the digest after the one running now. Appended rather than replacing, because replacing would
     * cancel the run that is asking.
     */
    fun scheduleNextAfterCurrentRun() {
        schedule(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun schedule(policy: ExistingWorkPolicy) {
        val settings = settingsStore.getSettings()
        if (!settings.isEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val now = clock.now()
        val delay = nextRunAt(now, timeZoneProvider.current(), settings.sendTime) - now

        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, createRequest(delay.inWholeMilliseconds))
    }

    private fun createRequest(delayMillis: Long): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequestBuilder<SpamDigestWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
    }
}
