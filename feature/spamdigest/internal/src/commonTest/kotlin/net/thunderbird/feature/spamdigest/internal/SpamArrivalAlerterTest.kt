package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.core.testing.TestClock
import net.thunderbird.feature.spamdigest.SpamAlertNotifier
import net.thunderbird.feature.spamdigest.SpamMessage

class SpamArrivalAlerterTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val clock = TestClock(now)
    private val alertLog = FakeSpamAlertLog().apply { enabledAt["work"] = now - 1.days }
    private val notified = mutableListOf<String>()
    private val notifier = SpamAlertNotifier { accountId, _, messageServerId, _ ->
        notified.add("$accountId/$messageServerId")
    }

    private val testSubject = SpamArrivalAlerter(alertLog, notifier, clock, TestLogger())

    @Test
    fun `mail from someone known should be alerted about`() {
        testSubject.onSpamArrived("work", 1, "m1", spam(isFromKnownSender = true))

        assertThat(notified).containsExactly("work/m1")
    }

    @Test
    fun `mail from strangers should not be alerted about`() {
        testSubject.onSpamArrived("work", 1, "m1", spam(isFromKnownSender = false))

        assertThat(notified).isEmpty()
    }

    @Test
    fun `accounts with the alert off should not alert`() {
        testSubject.onSpamArrived("personal", 1, "m1", spam(isFromKnownSender = true))

        assertThat(notified).isEmpty()
    }

    @Test
    fun `a message should be alerted about once however often it is synced`() {
        repeat(3) { testSubject.onSpamArrived("work", 1, "m1", spam(isFromKnownSender = true)) }

        assertThat(notified).containsExactly("work/m1")
    }

    @Test
    fun `mail that sat in spam long before the alert was turned on should not be alerted about`() {
        alertLog.enabledAt["work"] = now - 10.minutes

        testSubject.onSpamArrived("work", 1, "old", spam(isFromKnownSender = true, receivedAt = now - 3.hours))
        testSubject.onSpamArrived("work", 1, "recent", spam(isFromKnownSender = true, receivedAt = now - 30.minutes))

        assertThat(notified).containsExactly("work/recent")
    }

    @Test
    fun `mail older than two days should be left to the digest`() {
        testSubject.onSpamArrived("work", 1, "m1", spam(isFromKnownSender = true, receivedAt = now - 3.days))

        assertThat(notified).isEmpty()
    }

    private fun spam(isFromKnownSender: Boolean, receivedAt: Instant = now - 5.minutes) = SpamMessage(
        senderName = "Jordan Colleague",
        senderAddress = "jordan@firm.example",
        subject = "Contract",
        receivedAt = receivedAt,
        senderChecks = emptyList(),
        isFromKnownSender = isFromKnownSender,
    )
}

internal class FakeSpamAlertLog : SpamAlertLog {
    val enabledAt = mutableMapOf<String, Instant>()
    private val alerted = mutableSetOf<String>()
    val syncTurnedOn = mutableSetOf<String>()

    override fun alertEnabledAt(accountId: String): Instant? = enabledAt[accountId]

    override fun markAlerted(key: String, at: Instant): Boolean = alerted.add(key)

    override fun isSyncTurnedOnByAlert(accountId: String): Boolean = accountId in syncTurnedOn

    override fun setSyncTurnedOnByAlert(accountId: String, turnedOn: Boolean) {
        if (turnedOn) syncTurnedOn += accountId else syncTurnedOn -= accountId
    }
}
