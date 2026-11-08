package net.thunderbird.app.common.feature.mailscan

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.packages.ShippingMailDownloader
import net.thunderbird.feature.trips.TravelMailDownloader

private const val WORK_NAME = "RecentMailDownload"
private const val INTERVAL_HOURS = 6L

/**
 * Downloads the bodies of recent shipping and booking mail that arrived as an envelope only, as Microsoft 365 mail
 * does, so Packages and Trips have read it by the time the reader opens them. Only for the screens turned on.
 */
// IMPORTANT: Update K9WorkerFactory when moving this class and the FQCN no longer starts with
// "net.thunderbird.app.common".
class RecentMailDownloadWorker internal constructor(
    private val generalSettingsManager: GeneralSettingsManager,
    private val shippingMailDownloader: ShippingMailDownloader,
    private val travelMailDownloader: TravelMailDownloader,
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val interaction = generalSettingsManager.getConfig().interaction
        try {
            if (interaction.isPackagesEnabled) shippingMailDownloader.downloadRecent()
            if (interaction.isTripsEnabled) travelMailDownloader.downloadRecent()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A store that cannot be read right now, e.g. while an account is being removed. Tried on the next run.
            Result.success()
        }
    }
}

/**
 * Runs [RecentMailDownloadWorker] every few hours, on a network that is not roaming and with the battery not low.
 * Mail that is already on the device costs one query and no download.
 */
internal class RecentMailDownloadScheduler(private val workManager: WorkManager) {
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<RecentMailDownloadWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_ROAMING)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
