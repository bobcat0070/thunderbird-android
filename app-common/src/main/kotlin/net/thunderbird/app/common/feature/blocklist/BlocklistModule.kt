package net.thunderbird.app.common.feature.blocklist

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.blocklist.internal.BlocklistSettingsProvider
import net.thunderbird.feature.blocklist.internal.DefaultBlocklistUpdater
import net.thunderbird.feature.blocklist.internal.androidBlocklistModule
import net.thunderbird.feature.blocklist.internal.featureBlocklistModule
import org.koin.dsl.module

private const val DAILY_WORK_NAME = "DomainBlocklistDailyUpdate"
private const val NOW_WORK_NAME = "DomainBlocklistUpdateNow"

internal val appCommonFeatureBlocklistModule = module {
    includes(featureBlocklistModule, androidBlocklistModule)

    single<BlocklistSettingsProvider> {
        val generalSettingsManager = get<GeneralSettingsManager>()
        BlocklistSettingsProvider { generalSettingsManager.getConfig().domainBlocklist }
    }
    factory { DomainBlocklistScheduler(workManager = get(), updater = get()) }
    factory { (parameters: WorkerParameters) ->
        DomainBlocklistUpdateWorker(updater = get(), context = get(), workerParams = parameters)
    }
}

/**
 * Keeps the blocklists current: once a day on an unmetered network, and straight away - on any network - when a
 * chosen list is not on the device yet, as on first start or after the user picks another tier.
 */
internal class DomainBlocklistScheduler(
    private val workManager: WorkManager,
    private val updater: DefaultBlocklistUpdater,
) {
    fun ensureScheduled() {
        val daily = PeriodicWorkRequestBuilder<DomainBlocklistUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(DAILY_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, daily)

        if (updater.isListMissing()) updateNow()
    }

    /**
     * Runs an update as soon as there is a network, replacing one already waiting, so a change of setting is
     * acted on with the newest choice. The update also drops lists no longer chosen.
     */
    fun updateNow() {
        val now = OneTimeWorkRequestBuilder<DomainBlocklistUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, now)
    }
}

/**
 * Downloads the chosen blocklists when they are missing or old; see [DomainBlocklistScheduler].
 */
// IMPORTANT: Update K9WorkerFactory when moving this class and the FQCN no longer starts with
// "net.thunderbird.app.common".
class DomainBlocklistUpdateWorker internal constructor(
    private val updater: DefaultBlocklistUpdater,
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (updater.update()) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A full disk or a list store that cannot be written right now. Tried again later.
            Result.retry()
        }
    }
}
