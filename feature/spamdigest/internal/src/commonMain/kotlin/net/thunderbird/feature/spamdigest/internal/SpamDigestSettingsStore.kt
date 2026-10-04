package net.thunderbird.feature.spamdigest.internal

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.thunderbird.feature.spamdigest.SpamDigestField
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import net.thunderbird.feature.spamdigest.SpamDigestTime
import net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync

/**
 * Where the digest's settings and its record of sent days are kept, without any effect on the schedule.
 */
internal interface SpamDigestSettingsStore : SpamDigestSettingsRepository, SpamDigestLog, SpamAlertLog {
    /**
     * @return when the digest's alarm was last set to go off, or `null` when none is set.
     */
    fun scheduledFor(): Instant?

    fun setScheduledFor(scheduledFor: Instant?)
}

/**
 * What the spam alert remembers between syncs.
 */
internal interface SpamAlertLog {
    /**
     * @return when the alert for [accountId] was last turned on, or `null` while it is off.
     */
    fun alertEnabledAt(accountId: String): Instant?

    /**
     * Records that a message was alerted about, and reports whether it had been already. Old records are dropped,
     * since a message arriving again after a week is not the same arrival.
     *
     * @return `true` when this is the first time.
     */
    fun markAlerted(key: String, at: Instant): Boolean

    /**
     * Whether the alert itself turned on background checking of [accountId]'s spam folder, so that turning the alert
     * off can undo it without undoing a choice the user made in the folder's own settings.
     */
    fun isSyncTurnedOnByAlert(accountId: String): Boolean

    fun setSyncTurnedOnByAlert(accountId: String, turnedOn: Boolean)
}

/**
 * The settings as the rest of the app changes them: every change that moves the digest reschedules it.
 *
 * A wrapper rather than a callback inside the store, because the scheduler itself reads the store, and the two
 * would otherwise each need the other to be built first.
 */
internal class ReschedulingSpamDigestSettingsRepository(
    private val store: SpamDigestSettingsStore,
    private val scheduler: SpamDigestScheduler,
    private val spamFolderBackgroundSync: SpamFolderBackgroundSync,
) : SpamDigestSettingsRepository {
    override fun getSettings(): SpamDigestSettings = store.getSettings()

    override fun setAccountIncluded(accountId: String, included: Boolean) {
        store.setAccountIncluded(accountId, included)
    }

    override fun setSenderAccount(accountId: String?) {
        store.setSenderAccount(accountId)
        scheduler.reschedule()
    }

    override fun setSendTime(time: SpamDigestTime) {
        store.setSendTime(time)
        scheduler.reschedule()
    }

    override fun setFields(fields: Set<SpamDigestField>) {
        store.setFields(fields)
    }

    override fun setAlertEnabled(accountId: String, enabled: Boolean) {
        store.setAlertEnabled(accountId, enabled)

        if (enabled) {
            if (spamFolderBackgroundSync.isSyncEnabled(accountId) == false) {
                spamFolderBackgroundSync.setSyncEnabled(accountId, true)
                store.setSyncTurnedOnByAlert(accountId, true)
            }
        } else if (store.isSyncTurnedOnByAlert(accountId)) {
            spamFolderBackgroundSync.setSyncEnabled(accountId, false)
            store.setSyncTurnedOnByAlert(accountId, false)
        }
    }
}

/**
 * The time zone the digest's days and send time are read in: the device's, as it is at the moment of asking, so
 * that travelling moves the digest with the reader.
 */
internal fun interface TimeZoneProvider {
    fun current(): TimeZone
}
