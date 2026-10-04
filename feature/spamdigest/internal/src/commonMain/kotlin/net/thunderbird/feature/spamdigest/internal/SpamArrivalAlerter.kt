package net.thunderbird.feature.spamdigest.internal

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamAlertNotifier
import net.thunderbird.feature.spamdigest.SpamArrivalListener
import net.thunderbird.feature.spamdigest.SpamMessage

private const val LOG_TAG = "SpamAlert"

/**
 * The oldest mail an alert is raised for. A sync that catches up after days offline brings in mail the reader no
 * longer needs to hear about one message at a time; the digest covers it.
 */
private val MAXIMUM_AGE = 2.days

/**
 * How far before the alert was turned on a message may have arrived and still be alerted about. Turning the alert
 * on also starts checking the spam folder, and its first check finds everything already there; only what came in
 * shortly before is news.
 */
private val GRACE_BEFORE_ENABLED = 1.hours

/**
 * Raises an alert when mail from someone the reader knows lands in an account's spam folder.
 *
 * Who counts as known is decided before this is called - see [SpamMessage.isFromKnownSender] - and already excludes
 * mail that failed DMARC or impersonates the person, so a spoofer cannot use the alert to put their message in
 * front of the reader.
 */
internal class SpamArrivalAlerter(
    private val alertLog: SpamAlertLog,
    private val notifier: SpamAlertNotifier,
    private val clock: Clock,
    private val logger: Logger,
) : SpamArrivalListener {

    override fun onSpamArrived(accountId: String, folderId: Long, messageServerId: String, message: SpamMessage) {
        if (!message.isFromKnownSender) return

        val enabledAt = alertLog.alertEnabledAt(accountId) ?: return
        val now = clock.now()
        if (message.receivedAt < enabledAt - GRACE_BEFORE_ENABLED || now - message.receivedAt > MAXIMUM_AGE) return

        if (!alertLog.markAlerted("$accountId/$folderId/$messageServerId", now)) return

        logger.info(LOG_TAG) { "Alerting about mail from a known sender in a spam folder" }
        notifier.notify(accountId, folderId, messageServerId, message)
    }
}
