package net.thunderbird.app.common.feature.todaysummary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.todaysummary.TodaySummaryJob

private const val WORK_NAME = "TodaySummary"
private const val DAY_HOURS = 24L

/**
 * Shows the summary of the day, while it is turned on in General settings.
 */
// IMPORTANT: Update K9WorkerFactory when moving this class and the FQCN no longer starts with
// "net.thunderbird.app.common".
class TodaySummaryWorker internal constructor(
    private val generalSettingsManager: GeneralSettingsManager,
    private val job: TodaySummaryJob,
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun doWork(): Result {
        if (!generalSettingsManager.getConfig().interaction.isTodaySummaryEnabled) return Result.success()

        return try {
            job.showSummary()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A store that cannot be read right now; the next morning tries again.
            Result.success()
        }
    }
}

/**
 * Runs [TodaySummaryWorker] once a day at the chosen time while the summary is turned on, and not at all while it
 * is off. Called at startup and whenever the switch or the time changes, so the next run is set from the setting as
 * it is now.
 *
 * WorkManager may run it a few minutes late when the phone is asleep; a summary of the day does not need to be on the
 * minute, and does not need the exact alarm permission.
 */
internal class TodaySummaryScheduler(
    private val workManager: WorkManager,
    private val generalSettingsManager: GeneralSettingsManager,
    private val job: TodaySummaryJob,
) {
    fun update() {
        val interaction = generalSettingsManager.getConfig().interaction
        if (!interaction.isTodaySummaryEnabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<TodaySummaryWorker>(DAY_HOURS, TimeUnit.HOURS)
            .setInitialDelay(job.millisUntilNext(interaction.todaySummaryTime), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }
}
