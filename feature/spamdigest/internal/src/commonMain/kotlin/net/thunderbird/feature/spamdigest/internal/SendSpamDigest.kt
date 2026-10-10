package net.thunderbird.feature.spamdigest.internal

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamDigestAccounts
import net.thunderbird.feature.spamdigest.SpamDigestMailer
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import net.thunderbird.feature.spamdigest.SpamFolderReader

private const val LOG_TAG = "SpamDigest"

/**
 * Remembers which day was last reported on, so a run that is repeated - a retry, or a schedule that fires twice
 * around a clock change - does not send the same digest again.
 */
internal interface SpamDigestLog {
    fun lastSentDay(): LocalDate?
    fun markSent(day: LocalDate)
}

/**
 * Sends the digest of the spam that arrived since the last one, if one is due: yesterday's, normally, and also any
 * earlier day whose digest did not go out.
 */
internal class SendSpamDigest(
    private val settingsRepository: SpamDigestSettingsRepository,
    private val digestLog: SpamDigestLog,
    private val accounts: SpamDigestAccounts,
    private val spamFolderReader: SpamFolderReader,
    private val mailer: SpamDigestMailer,
    private val composer: SpamDigestComposer,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val logger: Logger,
) {
    enum class Outcome {
        /** The digest is turned off. */
        OFF,

        /** The account the digest is sent from no longer exists. */
        SENDER_MISSING,

        /** Every day due has been reported on already. */
        ALREADY_SENT,
        SENT,
    }

    /**
     * @throws Exception when the digest could not be handed over for sending, so the run can be retried.
     */
    suspend operator fun invoke(): Outcome {
        val settings = settingsRepository.getSettings()
        val senderAccountId = settings.senderAccountId ?: return Outcome.OFF

        val allAccounts = accounts.accounts()
        if (allAccounts.none { it.id == senderAccountId }) {
            logger.warn(LOG_TAG) { "The account the spam digest is sent from no longer exists" }
            return Outcome.SENDER_MISSING
        }

        val timeZone = timeZoneProvider.current()
        val period = reportingPeriod(clock.now(), timeZone, settings.sendTime, digestLog.lastSentDay())
        if (period == null) {
            logger.info(LOG_TAG) { "Spam digest already sent for every day due" }
            return Outcome.ALREADY_SENT
        }

        val accountSpam = allAccounts
            .filter { settings.isAccountIncluded(it.id) }
            .map { account -> AccountSpam(account, readSpamFolder(account.id, period)) }

        val digest = composer.compose(
            day = period.firstDate,
            accounts = accountSpam,
            fields = settings.fields,
            lastDay = period.lastDate,
            timeZone = timeZone,
        )
        mailer.sendToSelf(senderAccountId, digest.subject, digest.body)
        digestLog.markSent(period.lastDate)

        logger.info(LOG_TAG) {
            "Spam digest queued for ${accountSpam.size} accounts, covering ${period.dayCount()} days"
        }

        return Outcome.SENT
    }

    private suspend fun readSpamFolder(accountId: String, period: DigestPeriod): SpamFolderResult {
        return try {
            spamFolderReader.read(accountId, period.start, period.end)
                ?.let { SpamFolderResult.Read(it) }
                ?: SpamFolderResult.NoSpamFolder
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // One account failing should not hold back the others: its section says it could not be read. Only the
            // exception's type is logged, since server error messages can carry addresses.
            logger.warn(LOG_TAG) { "Could not read a spam folder for the digest: ${e::class.simpleName}" }
            SpamFolderResult.Unreadable
        }
    }
}

private fun DigestPeriod.dayCount(): Int = firstDate.daysUntil(lastDate) + 1
