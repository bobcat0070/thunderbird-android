package net.thunderbird.feature.spamdigest.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamDigestWorkNotification

private const val LOG_TAG = "SpamDigest"
private const val MAX_ATTEMPTS = 5

/**
 * Sends one digest. Started by the digest's alarm; the next one is scheduled by the alarm, not here.
 */
// IMPORTANT: Update K9WorkerFactory when moving this class out of the "net.thunderbird.feature.spamdigest" package.
// Public only so the apps' dependency injection tests can name it; WorkManager creates it by class name.
class SpamDigestWorker internal constructor(
    private val sendSpamDigest: SendSpamDigest,
    private val workNotification: SpamDigestWorkNotification,
    private val logger: Logger,
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        logger.info(LOG_TAG) { "Spam digest run started, attempt ${runAttemptCount + 1}" }

        return try {
            val outcome = sendSpamDigest()
            logger.info(LOG_TAG) { "Spam digest run finished: $outcome" }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn(LOG_TAG) { "Spam digest attempt $runAttemptCount failed: ${e::class.simpleName}" }

            // After a few tries this day is given up on; tomorrow's alarm is already set.
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    /**
     * Asked for only on Android versions that run urgent work as a foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(workNotification.notificationId, workNotification.create())
    }
}
