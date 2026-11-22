package net.thunderbird.feature.impersonation.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Instant
import net.thunderbird.core.testing.TestClock
import net.thunderbird.feature.impersonation.KnownSenders
import net.thunderbird.feature.impersonation.KnownSendersSource
import net.thunderbird.feature.impersonation.ReplyToMismatch
import net.thunderbird.feature.impersonation.StoredMailCounter

class SenderNoteCheckersTest {
    private var correspondentAddresses = emptySet<String>()
    private val correspondents = Correspondents(
        knownSendersSource = KnownSendersSource { KnownSenders(emptyMap(), correspondentAddresses, emptySet()) },
        clock = TestClock(Instant.parse("2026-11-21T12:00:00Z")),
    )

    @Test
    fun `replies going to another domain should be pointed out`() {
        val testSubject = CorrespondentsReplyToChecker(correspondents)

        val result = testSubject.check("billing@supplier.example", listOf("billing.supplier@freemail.example"))

        assertThat(result).isEqualTo(
            ReplyToMismatch(
                replyToAddress = "billing.supplier@freemail.example",
                senderAddress = "billing@supplier.example",
                senderDomain = "supplier.example",
            ),
        )
    }

    @Test
    fun `replies going to the sender's own domain should not be pointed out`() {
        val testSubject = CorrespondentsReplyToChecker(correspondents)

        val result = testSubject.check("news@mail.shop.example", listOf("Help@Support.Shop.Example"))

        assertThat(result).isNull()
    }

    @Test
    fun `replies going to someone the reader writes to should not be pointed out`() {
        correspondentAddresses = setOf("Alex@Freemail.example")
        val testSubject = CorrespondentsReplyToChecker(correspondents)

        val result = testSubject.check("alex@work.example", listOf("alex@freemail.example"))

        assertThat(result).isNull()
    }

    @Test
    fun `a known sender should not clear replies going to a stranger`() {
        // A forged invoice from a supplier the reader already pays is the case the warning is for.
        correspondentAddresses = setOf("billing@supplier.example")
        val testSubject = CorrespondentsReplyToChecker(correspondents)

        val result = testSubject.check("billing@supplier.example", listOf("payments@elsewhere.example"))

        assertThat(result?.replyToAddress).isEqualTo("payments@elsewhere.example")
    }

    @Test
    fun `the first reply address that stands out should be named`() {
        val result = findReplyToMismatch(
            senderAddress = "team@company.example",
            replyToAddresses = listOf("team@company.example", "", "someone@other.example"),
            isCorrespondent = { false },
        )

        assertThat(result?.replyToAddress).isEqualTo("someone@other.example")
    }

    @Test
    fun `a message without a usable sender should not be checked`() {
        assertThat(findReplyToMismatch(null, listOf("a@other.example")) { false }).isNull()
        assertThat(findReplyToMismatch("no-domain", listOf("a@other.example")) { false }).isNull()
    }

    @Test
    fun `a reply address without a domain should not be pointed out`() {
        val result = findReplyToMismatch("team@company.example", listOf("undisclosed-recipients")) { false }

        assertThat(result).isNull()
    }

    @Test
    fun `a sender with no other stored mail should be new`() {
        val testSubject = StoredMailFirstTimeSenderChecker(correspondents, counter(storedMessages = 1))

        assertThat(testSubject.isFirstMail("Someone@New.example")).isTrue()
    }

    @Test
    fun `a sender with other stored mail should not be new`() {
        val testSubject = StoredMailFirstTimeSenderChecker(correspondents, counter(storedMessages = 7))

        assertThat(testSubject.isFirstMail("someone@known.example")).isFalse()
    }

    @Test
    fun `someone the reader has written to should not be new`() {
        correspondentAddresses = setOf("someone@known.example")
        val testSubject = StoredMailFirstTimeSenderChecker(correspondents, counter(storedMessages = 1))

        assertThat(testSubject.isFirstMail("someone@known.example")).isFalse()
    }

    @Test
    fun `stored mail should be counted no further than it takes to tell`() {
        var limit = 0
        val testSubject = StoredMailFirstTimeSenderChecker(
            correspondents,
            StoredMailCounter { _, atMost ->
                limit = atMost
                atMost
            },
        )

        testSubject.isFirstMail("someone@example.com")

        assertThat(limit).isEqualTo(2)
    }

    private fun counter(storedMessages: Int) = StoredMailCounter { _, atMost -> minOf(storedMessages, atMost) }
}
