package net.thunderbird.app.common.feature.spamdigest

import android.content.Context
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import kotlin.coroutines.cancellation.CancellationException
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamDigestScheduler

private const val LOG_TAG = "SpamDigest"

/**
 * Sends an overdue spam digest after each mail check.
 *
 * The digest's alarm, or the work it starts, does not always run: a digest went missing every few days. Mail checks
 * run every few minutes whatever happens to that alarm, so one of them notices and sends what was missed.
 */
internal class SpamDigestCatchUpListener(
    private val spamDigestScheduler: SpamDigestScheduler,
    private val logger: Logger,
) : SimpleMessagingListener() {

    override fun checkMailFinished(context: Context?, account: LegacyAccountDto?) {
        try {
            spamDigestScheduler.catchUpIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Catching up must never fail the mail check it rides on.
            logger.warn(LOG_TAG) { "Could not check for an overdue spam digest: ${e::class.simpleName}" }
        }
    }
}
