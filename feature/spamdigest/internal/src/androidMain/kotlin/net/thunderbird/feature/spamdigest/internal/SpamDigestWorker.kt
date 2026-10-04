package net.thunderbird.feature.spamdigest.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import net.thunderbird.core.logging.Logger

private const val LOG_TAG = "SpamDigest"
private const val MAX_ATTEMPTS = 5

/**
 * Sends one digest and schedules the next.
 */
// IMPORTANT: Update K9WorkerFactory when moving this class out of the "net.thunderbird.feature.spamdigest" package.
// Public only so the apps' dependency injection tests can name it; WorkManager creates it by class name.
class SpamDigestWorker internal constructor(
    private val sendSpamDigest: SendSpamDigest,
    private val scheduler: WorkManagerSpamDigestScheduler,
    private val logger: Logger,
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val outcome = try {
            sendSpamDigest()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn(LOG_TAG) { "Spam digest attempt $runAttemptCount failed: ${e::class.simpleName}" }

            // A failed run must not end the chain, or no digest would ever be sent again. After a few tries this
            // day is given up on and tomorrow's is scheduled.
            if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
            null
        }

        when (outcome) {
            SendSpamDigest.Outcome.OFF, SendSpamDigest.Outcome.SENDER_MISSING -> Unit
            else -> scheduler.scheduleNextAfterCurrentRun()
        }

        return Result.success()
    }
}
