package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestTime
import net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync

class ReschedulingSpamDigestSettingsRepositoryTest {
    private val store = FakeSettingsStore()
    private val backgroundSync = FakeBackgroundSync()
    private val scheduler = FakeScheduler()
    private val testSubject = ReschedulingSpamDigestSettingsRepository(store, scheduler, backgroundSync)

    @Test
    fun `turning the alert on should start checking a spam folder that was not being checked`() {
        backgroundSync.enabled["work"] = false

        testSubject.setAlertEnabled("work", enabled = true)

        assertThat(backgroundSync.enabled["work"]).isEqualTo(true)
        assertThat(store.getSettings().alertAccountIds).isEqualTo(setOf("work"))
    }

    @Test
    fun `turning the alert off should stop the checking it started`() {
        backgroundSync.enabled["work"] = false
        testSubject.setAlertEnabled("work", enabled = true)

        testSubject.setAlertEnabled("work", enabled = false)

        assertThat(backgroundSync.enabled["work"]).isEqualTo(false)
        assertThat(store.isSyncTurnedOnByAlert("work")).isFalse()
    }

    @Test
    fun `turning the alert off should leave checking the user had turned on themselves`() {
        backgroundSync.enabled["work"] = true
        testSubject.setAlertEnabled("work", enabled = true)

        testSubject.setAlertEnabled("work", enabled = false)

        assertThat(backgroundSync.enabled["work"]).isEqualTo(true)
    }

    @Test
    fun `changing the send time should reschedule the digest`() {
        testSubject.setSendTime(SpamDigestTime(hour = 6, minute = 30))

        assertThat(scheduler.rescheduled).isTrue()
    }

    private class FakeBackgroundSync : SpamFolderBackgroundSync {
        val enabled = mutableMapOf<String, Boolean>()

        override fun isSyncEnabled(accountId: String): Boolean? = enabled[accountId]

        override fun setSyncEnabled(accountId: String, enabled: Boolean) {
            this.enabled[accountId] = enabled
        }
    }

    private class FakeScheduler : SpamDigestScheduler {
        var rescheduled = false

        override fun reschedule() {
            rescheduled = true
        }

        override fun ensureScheduled() = Unit
    }

    private class FakeSettingsStore : SpamDigestSettingsStore, SpamAlertLog by FakeSpamAlertLog() {
        private var settings = SpamDigestSettings(null, emptySet(), SpamDigestTime.DEFAULT)

        override fun getSettings(): SpamDigestSettings = settings

        override fun setAccountIncluded(accountId: String, included: Boolean) = Unit

        override fun setSenderAccount(accountId: String?) {
            settings = settings.copy(senderAccountId = accountId)
        }

        override fun setSendTime(time: SpamDigestTime) {
            settings = settings.copy(sendTime = time)
        }

        override fun setAlertEnabled(accountId: String, enabled: Boolean) {
            val accounts = settings.alertAccountIds
            settings = settings.copy(alertAccountIds = if (enabled) accounts + accountId else accounts - accountId)
        }

        override fun lastSentDay(): LocalDate? = null

        override fun scheduledFor(): Instant? = null

        override fun setScheduledFor(scheduledFor: Instant?) = Unit

        override fun markSent(day: LocalDate) = Unit
    }
}
