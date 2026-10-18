package net.thunderbird.app.common.feature.impersonation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans stored mail for the verified sender domains and saves them; see [VerifiedSenderDomainsScheduler].
 */
// IMPORTANT: Update K9WorkerFactory when moving this class and the FQCN no longer starts with
// "net.thunderbird.app.common".
class VerifiedSenderDomainsWorker internal constructor(
    private val scanner: VerifiedSenderDomainScanner,
    private val store: VerifiedSenderDomainStore,
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            store.save(scanner.scan())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A store that cannot be read right now, e.g. while an account is being removed. Tried again later.
            Result.retry()
        }
    }
}
