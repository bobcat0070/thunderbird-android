package net.thunderbird.feature.spamdigest.internal

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.single
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.core.testing.TestClock
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamDigestField
import net.thunderbird.feature.spamdigest.SpamDigestMailer
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent.Kind
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import net.thunderbird.feature.spamdigest.SpamDigestTime
import net.thunderbird.feature.spamdigest.SpamFolderContents
import net.thunderbird.feature.spamdigest.SpamFolderReader
import net.thunderbird.feature.spamdigest.SpamMessage

class SendSpamDigestTest {
    private val work = SpamDigestAccount(id = "work", name = "Work", email = "me@work.example")
    private val personal = SpamDigestAccount(id = "personal", name = "Personal", email = "me@home.example")

    private val settingsRepository = FakeSettingsRepository(
        SpamDigestSettings(
            senderAccountId = "work",
            excludedAccountIds = emptySet(),
            sendTime = SpamDigestTime.DEFAULT,
        ),
    )
    private val digestLog = FakeDigestLog()
    private val spamFolderReader = FakeSpamFolderReader()
    private val mailer = FakeMailer()
    private val runLog = FakeRunLog()
    private val clock = TestClock(Instant.parse("2026-10-03T07:00:00Z"))

    private val testSubject = SendSpamDigest(
        settingsRepository = settingsRepository,
        digestLog = digestLog,
        accounts = { listOf(work, personal) },
        spamFolderReader = spamFolderReader,
        mailer = mailer,
        composer = SpamDigestComposer(FakeSpamDigestStrings()),
        clock = clock,
        timeZoneProvider = { TimeZone.UTC },
        logger = TestLogger(),
        runLog = runLog,
    )

    @Test
    fun `should send nothing when the digest is off`() = runTest {
        settingsRepository.current = settingsRepository.current.copy(senderAccountId = null)

        val outcome = testSubject()

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.OFF)
        assertThat(mailer.sent).isEmpty()
    }

    @Test
    fun `should send nothing when the sending account was removed`() = runTest {
        settingsRepository.current = settingsRepository.current.copy(senderAccountId = "removed")

        val outcome = testSubject()

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.SENDER_MISSING)
        assertThat(mailer.sent).isEmpty()
    }

    @Test
    fun `should send yesterday's spam of every included account from the sending account`() = runTest {
        spamFolderReader.contents["work"] = SpamFolderContents(listOf(spamMessage("Work spam")), isRefreshed = true)
        spamFolderReader.contents["personal"] =
            SpamFolderContents(listOf(spamMessage("Personal spam")), isRefreshed = true)

        val outcome = testSubject()

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.SENT)
        assertThat(spamFolderReader.requests).isEqualTo(
            listOf(
                Triple("work", Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z")),
                Triple("personal", Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z")),
            ),
        )
        assertThat(mailer.sent).single().all {
            transform { it.accountId }.isEqualTo("work")
            transform { it.subject }.isEqualTo("Spam digest for 2026-10-02: 2")
            transform { it.body }.contains("Subject: Work spam")
            transform { it.body }.contains("Subject: Personal spam")
        }
        assertThat(digestLog.lastSentDay()).isEqualTo(LocalDate(2026, 10, 2))
    }

    @Test
    fun `should also report on days whose digest did not go out`() = runTest {
        // The last digest covered Sep 30; the one for Oct 1 never went out.
        digestLog.markSent(LocalDate(2026, 9, 30))

        val outcome = testSubject()

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.SENT)
        assertThat(spamFolderReader.requests.first()).isEqualTo(
            Triple("work", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z")),
        )
        assertThat(mailer.sent).single().transform { it.subject }
            .isEqualTo("Spam digest for 2026-10-01 to 2026-10-02: 0")
        assertThat(digestLog.lastSentDay()).isEqualTo(LocalDate(2026, 10, 2))
    }

    @Test
    fun `should leave out excluded accounts`() = runTest {
        settingsRepository.current = settingsRepository.current.copy(excludedAccountIds = setOf("personal"))

        testSubject()

        assertThat(spamFolderReader.requests.map { it.first }).isEqualTo(listOf("work"))
        assertThat(mailer.sent).single().transform { it.body }.doesNotContain("Personal")
    }

    @Test
    fun `should not send the same day twice`() = runTest {
        digestLog.markSent(LocalDate(2026, 10, 2))

        val outcome = testSubject()

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.ALREADY_SENT)
        assertThat(mailer.sent).isEmpty()
    }

    @Test
    fun `should still send the other accounts when one spam folder cannot be read`() = runTest {
        spamFolderReader.failingAccountIds += "work"

        val outcome = testSubject()

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.SENT)
        assertThat(mailer.sent).single().transform { it.body }.contains("Unreadable.")
    }

    @Test
    fun `should record what each run did, so a missing digest can be explained`() = runTest {
        testSubject()
        testSubject()
        settingsRepository.current = settingsRepository.current.copy(senderAccountId = "removed")
        testSubject()

        assertThat(runLog.kinds).isEqualTo(
            listOf(Kind.READ, Kind.SENDING, Kind.SENT, Kind.ALREADY_SENT, Kind.SENDER_MISSING),
        )
        assertThat(runLog.detailOf(Kind.SENDING)).isEqualTo("0/2026-10-02")
        assertThat(runLog.detailOf(Kind.SENT)).isEqualTo("2026-10-02/0")
    }

    @Test
    fun `should record how many messages were found and which folders could not be read`() = runTest {
        spamFolderReader.contents["work"] = SpamFolderContents(
            listOf(spamMessage("One"), spamMessage("Two")),
            isRefreshed = false,
        )
        spamFolderReader.failingAccountIds += "personal"

        testSubject()

        // Messages, accounts, not checked with the server, unreadable, without a spam folder.
        assertThat(runLog.detailOf(Kind.READ)).isEqualTo("2/2/1/1/0")
        assertThat(runLog.detailOf(Kind.SENDING)).isEqualTo("2/2026-10-02")
    }

    @Test
    fun `should send the last day again when asked for a digest that was sent already`() = runTest {
        // The reader asked for one: a digest that was recorded as sent but never arrived must be sendable.
        digestLog.markSent(LocalDate(2026, 10, 2))

        val outcome = testSubject(force = true)

        assertThat(outcome).isEqualTo(SendSpamDigest.Outcome.SENT)
        assertThat(mailer.sent).single().transform { it.subject }.isEqualTo("Spam digest for 2026-10-02: 0")
        assertThat(digestLog.lastSentDay()).isEqualTo(LocalDate(2026, 10, 2))
    }

    @Test
    fun `should cover every missed day when asked for a digest that is overdue`() = runTest {
        digestLog.markSent(LocalDate(2026, 9, 30))

        testSubject(force = true)

        assertThat(mailer.sent).single().transform { it.subject }
            .isEqualTo("Spam digest for 2026-10-01 to 2026-10-02: 0")
        assertThat(runLog.detailOf(Kind.SENT)).isEqualTo("2026-10-01 – 2026-10-02/0")
    }

    @Test
    fun `should not record the day as sent when sending fails`() = runTest {
        mailer.failure = IllegalStateException("offline")

        assertFailure { testSubject() }

        assertThat(digestLog.lastSentDay()).isNull()
        assertThat(mailer.sent).hasSize(0)
    }

    private fun spamMessage(subject: String) = SpamMessage(
        senderName = "Sender",
        senderAddress = "sender@spam.example",
        subject = subject,
        receivedAt = Instant.parse("2026-10-02T12:00:00Z"),
        senderChecks = emptyList(),
    )
}

private class FakeSettingsRepository(var current: SpamDigestSettings) : SpamDigestSettingsRepository {
    override fun getSettings(): SpamDigestSettings = current

    override fun setAccountIncluded(accountId: String, included: Boolean) {
        val excluded = current.excludedAccountIds
        current = current.copy(excludedAccountIds = if (included) excluded - accountId else excluded + accountId)
    }

    override fun setSenderAccount(accountId: String?) {
        current = current.copy(senderAccountId = accountId)
    }

    override fun setSendTime(time: SpamDigestTime) {
        current = current.copy(sendTime = time)
    }

    override fun setFields(fields: Set<SpamDigestField>) {
        current = current.copy(fields = fields)
    }

    override fun setAlertEnabled(accountId: String, enabled: Boolean) {
        val accounts = current.alertAccountIds
        current = current.copy(alertAccountIds = if (enabled) accounts + accountId else accounts - accountId)
    }
}

private class FakeDigestLog : SpamDigestLog {
    private var lastSentDay: LocalDate? = null

    override fun lastSentDay(): LocalDate? = lastSentDay

    override fun markSent(day: LocalDate) {
        lastSentDay = day
    }
}

private class FakeSpamFolderReader : SpamFolderReader {
    val contents = mutableMapOf<String, SpamFolderContents>()
    val failingAccountIds = mutableSetOf<String>()
    val requests = mutableListOf<Triple<String, Instant, Instant>>()

    override suspend fun read(accountId: String, from: Instant, until: Instant): SpamFolderContents {
        requests += Triple(accountId, from, until)
        if (accountId in failingAccountIds) error("cannot read")

        return contents[accountId] ?: SpamFolderContents(emptyList(), isRefreshed = true)
    }
}

private data class SentDigest(val accountId: String, val subject: String, val body: String)

private class FakeMailer : SpamDigestMailer {
    val sent = mutableListOf<SentDigest>()
    var failure: Exception? = null

    override suspend fun sendToSelf(accountId: String, subject: String, body: String) {
        failure?.let { throw it }
        sent += SentDigest(accountId, subject, body)
    }
}

private class FakeRunLog : SpamDigestRunLog {
    private val events = mutableListOf<SpamDigestRunEvent>()

    val kinds: List<Kind> get() = events.map { it.kind }
    fun detailOf(kind: Kind): String? = events.single { it.kind == kind }.detail

    override fun record(kind: Kind, detail: String?) {
        events += SpamDigestRunEvent(Instant.parse("2026-10-03T07:00:00Z"), kind, detail)
    }

    override fun recentEvents(): List<SpamDigestRunEvent> = events.reversed()
}
