package net.thunderbird.feature.spamdigest.internal

import kotlinx.datetime.TimeZone
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import net.thunderbird.feature.spamdigest.SpamDigestTime

/**
 * Where the digest's settings and its record of sent days are kept, without any effect on the schedule.
 */
internal interface SpamDigestSettingsStore : SpamDigestSettingsRepository, SpamDigestLog

/**
 * The settings as the rest of the app changes them: every change that moves the digest reschedules it.
 *
 * A wrapper rather than a callback inside the store, because the scheduler itself reads the store, and the two
 * would otherwise each need the other to be built first.
 */
internal class ReschedulingSpamDigestSettingsRepository(
    private val store: SpamDigestSettingsStore,
    private val scheduler: SpamDigestScheduler,
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
}

/**
 * The time zone the digest's days and send time are read in: the device's, as it is at the moment of asking, so
 * that travelling moves the digest with the reader.
 */
internal fun interface TimeZoneProvider {
    fun current(): TimeZone
}
