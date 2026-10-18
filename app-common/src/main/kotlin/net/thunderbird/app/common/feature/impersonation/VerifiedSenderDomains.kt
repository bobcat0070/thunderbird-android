package net.thunderbird.app.common.feature.impersonation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.k9mail.legacy.mailstore.MessageStoreManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.thunderbird.core.android.account.LegacyAccountManager

private const val PREFERENCES_NAME = "verified_sender_domains"
private const val KEY_DOMAINS = "domains"

private const val NIGHTLY_WORK_NAME = "VerifiedSenderDomainsNightly"
private const val FIRST_WORK_NAME = "VerifiedSenderDomainsFirst"

/**
 * The hour of the night the scan runs, when the phone is usually idle and charging.
 */
private const val NIGHTLY_SCAN_HOUR = 3

/**
 * The domains of senders whose mail passed DMARC, as the last scan of stored mail found them.
 */
internal interface VerifiedSenderDomainStore {
    /**
     * @return the domains, or `null` when stored mail has not been scanned for them yet.
     */
    fun domains(): Set<String>?

    fun save(domains: Set<String>)
}

internal class SharedPreferencesVerifiedSenderDomainStore(context: Context) : VerifiedSenderDomainStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun domains(): Set<String>? = preferences.getStringSet(KEY_DOMAINS, null)?.toSet()

    override fun save(domains: Set<String>) {
        preferences.edit().putStringSet(KEY_DOMAINS, domains).commit()
    }
}

/**
 * Reads the domains of senders whose mail passed DMARC from stored mail, outside spam and trash.
 *
 * A spammer passing DMARC for their own throwaway domain proves nothing about it, and counting their domain as known
 * would let them borrow a known person's name from it.
 *
 * Reads every authenticated message of every account, so it runs once a night rather than whenever the known senders
 * are read: that was every half hour, on whichever message list happened to load next.
 */
internal class VerifiedSenderDomainScanner(
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
) {
    fun scan(): Set<String> {
        return accountManager.findAll().flatMapTo(mutableSetOf()) { account ->
            messageStoreManager.getMessageStore(account.id).getMessages(
                selection = "messages.sender_authenticated = 1 AND folders.type NOT IN ('spam', 'trash')",
                selectionArgs = emptyArray(),
                sortOrder = "messages.id",
                messageMapper = { message ->
                    message.fromAddresses.firstOrNull()?.address?.substringAfterLast('@', missingDelimiterValue = "")
                        ?.lowercase()?.takeIf { it.isNotEmpty() }
                },
            )
        }
    }
}

/**
 * Keeps the verified sender domains scanned once a night, and straight away while none are saved: never scanned, or
 * scanned before there was any mail - on a new install, the first start runs before an account is set up.
 *
 * A domain that first sends authenticated mail during the day counts from the next night's scan. People the reader
 * writes to need no scan: the recipient index records them as the mail is sent.
 */
internal class VerifiedSenderDomainsScheduler(
    private val workManager: WorkManager,
    private val store: VerifiedSenderDomainStore,
    private val clock: Clock,
) {
    fun ensureScheduled() {
        val now = clock.now()
        val initialDelay = nextNightlyScanAt(now, TimeZone.currentSystemDefault()) - now
        val nightly = PeriodicWorkRequestBuilder<VerifiedSenderDomainsWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(NIGHTLY_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, nightly)

        if (store.domains().isNullOrEmpty()) {
            val first = OneTimeWorkRequestBuilder<VerifiedSenderDomainsWorker>().build()
            workManager.enqueueUniqueWork(FIRST_WORK_NAME, ExistingWorkPolicy.KEEP, first)
        }
    }
}

/**
 * The next time strictly after [now] that the clock in [timeZone] reads three in the morning.
 */
internal fun nextNightlyScanAt(now: Instant, timeZone: TimeZone): Instant {
    val today = now.toLocalDateTime(timeZone).date
    val scanTime = LocalTime(NIGHTLY_SCAN_HOUR, 0)

    val tonight = LocalDateTime(today, scanTime).toInstant(timeZone)
    if (tonight > now) return tonight

    return LocalDateTime(today.plus(1, DateTimeUnit.DAY), scanTime).toInstant(timeZone)
}
