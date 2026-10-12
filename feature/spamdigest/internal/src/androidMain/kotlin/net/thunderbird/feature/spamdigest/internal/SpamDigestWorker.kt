package net.thunderbird.feature.spamdigest.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent
import net.thunderbird.feature.spamdigest.SpamDigestWorkNotification

private const val LOG_TAG = "SpamDigest"
private const val MAX_ATTEMPTS = 5

/**
 * Input of a run: whether the reader asked for a digest, which sends one even when every day due was reported on.
 */
internal const val KEY_FORCE = "force"

/**
 * Sends one digest. Started by the digest's alarm; the next one is scheduled by the alarm, not here.
 */
// IMPORTANT: Update K9WorkerFactory when moving this class out of the "net.thunderbird.feature.spamdigest" package.
// Public only so the apps' dependency injection tests can name it; WorkManager creates it by class name.
class SpamDigestWorker internal constructor(
    private val sendSpamDigest: SendSpamDigest,
    private val workNotification: SpamDigestWorkNotification,
    private val logger: Logger,
    private val runLog: SpamDigestRunLog,
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val attempt = runAttemptCount + 1
        logger.info(LOG_TAG) { "Spam digest run started, attempt $attempt" }
        runLog.record(SpamDigestRunEvent.Kind.STARTED, attempt.toString())

        return try {
            val outcome = sendSpamDigest(force = inputData.getBoolean(KEY_FORCE, false))
            logger.info(LOG_TAG) { "Spam digest run finished: $outcome" }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Only the kind of error: its message can carry an address.
            val error = e::class.simpleName
            logger.warn(LOG_TAG) { "Spam digest attempt $attempt failed: $error" }

            // After a few tries this day is given up on; tomorrow's alarm is already set.
            if (attempt < MAX_ATTEMPTS) {
                runLog.record(SpamDigestRunEvent.Kind.FAILED, error)
                Result.retry()
            } else {
                runLog.record(SpamDigestRunEvent.Kind.GAVE_UP, error)
                Result.success()
            }
        }
    }

    /**
     * Asked for only on Android versions that run urgent work as a foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(workNotification.notificationId, workNotification.create())
    }
}
