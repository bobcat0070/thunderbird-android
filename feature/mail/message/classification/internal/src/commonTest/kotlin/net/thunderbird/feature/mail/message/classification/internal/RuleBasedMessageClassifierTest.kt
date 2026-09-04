package net.thunderbird.feature.mail.message.classification.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence

class RuleBasedMessageClassifierTest {
    private val testSubject = RuleBasedMessageClassifier()

    @Test
    fun `message with no notable headers should be unknown`() {
        // Absence of bulk headers is not evidence of a person. In a real mailbox most automated mail carries
        // no Auto-Submitted header at all, so guessing human here would misfile constantly.
        val result = testSubject.classify(evidenceOf())

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
        assertThat(result.signal).isEqualTo(ClassificationSignal.NONE)
    }

    @Test
    fun `List-Unsubscribe should mean newsletter`() {
        val result = testSubject.classify(evidenceOf("list-unsubscribe" to "<https://example.com/u/1>"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
        assertThat(result.signal).isEqualTo(ClassificationSignal.BULK_HEADER)
    }

    @Test
    fun `Precedence bulk should mean newsletter`() {
        val result = testSubject.classify(evidenceOf("precedence" to "bulk"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
    }

    @Test
    fun `Precedence should be matched regardless of case`() {
        // Real mail carries both "bulk" and "Bulk".
        val result = testSubject.classify(evidenceOf("precedence" to "Bulk"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
    }

    @Test
    fun `unrelated Precedence value should not mean newsletter`() {
        val result = testSubject.classify(evidenceOf("precedence" to "first-class"))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    @Test
    fun `mailing list headers should mean newsletter`() {
        val result = testSubject.classify(evidenceOf("list-id" to "<dev.example.org>"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
        assertThat(result.signal).isEqualTo(ClassificationSignal.MAILING_LIST)
    }

    @Test
    fun `mailing list should outrank an automated header`() {
        // List traffic is written by people; a list server adding Auto-Submitted must not turn the whole list
        // into notifications.
        val result = testSubject.classify(
            evidenceOf(
                "list-post" to "<mailto:dev@example.org>",
                "auto-submitted" to "auto-generated",
            ),
        )

        assertThat(result.signal).isEqualTo(ClassificationSignal.MAILING_LIST)
    }

    @Test
    fun `Auto-Submitted should mean notification`() {
        val result = testSubject.classify(evidenceOf("auto-submitted" to "auto-generated"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
        assertThat(result.signal).isEqualTo(ClassificationSignal.AUTO_SUBMITTED)
    }

    @Test
    fun `Auto-Submitted no should not mean notification`() {
        // RFC 3834 uses "no" to say explicitly that a message was composed by a person.
        val result = testSubject.classify(evidenceOf("auto-submitted" to "no"))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    @Test
    fun `transactional mail carrying an unsubscribe link should still be a notification`() {
        // Bulk sender rules push senders to add List-Unsubscribe to receipts and alerts too, so the automated
        // headers have to be checked first or every receipt becomes a newsletter.
        val result = testSubject.classify(
            evidenceOf(
                "x-auto-response-suppress" to "All",
                "list-unsubscribe" to "<https://example.com/u/1>",
            ),
        )

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
        assertThat(result.signal).isEqualTo(ClassificationSignal.AUTO_RESPONSE_SUPPRESSED)
    }

    @Test
    fun `a sender the user has written to should be human`() {
        val result = testSubject.classify(evidenceOf(from = "sam@example.com", hasCorresponded = true))

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
        assertThat(result.signal).isEqualTo(ClassificationSignal.PRIOR_CORRESPONDENCE)
    }

    @Test
    fun `a sender in the address book should be human`() {
        val result = testSubject.classify(evidenceOf(from = "sam@example.com", isKnownContact = true))

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
        assertThat(result.signal).isEqualTo(ClassificationSignal.KNOWN_CONTACT)
    }

    @Test
    fun `correspondence should outrank the address book`() {
        // Having written to someone is a stronger statement than having their card.
        val result = testSubject.classify(
            evidenceOf(from = "sam@example.com", isKnownContact = true, hasCorresponded = true),
        )

        assertThat(result.signal).isEqualTo(ClassificationSignal.PRIOR_CORRESPONDENCE)
    }

    @Test
    fun `a known correspondent should outrank bulk headers`() {
        // The case this signal exists for: a colleague whose employer staples List-Unsubscribe onto
        // everything leaving the building.
        val result = testSubject.classify(
            evidenceOf(
                "list-unsubscribe" to "<https://example.com/u/1>",
                from = "sam@example.com",
                hasCorresponded = true,
            ),
        )

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
    }

    @Test
    fun `a known correspondent should outrank a no-reply address`() {
        val result = testSubject.classify(evidenceOf(from = "noreply@example.com", hasCorresponded = true))

        assertThat(result.messageClass).isEqualTo(MessageClass.HUMAN)
    }

    @Test
    fun `an automated message from a known correspondent should stay a notification`() {
        // A receipt from a shop the user emails is still a receipt, and the machine saying so outright is
        // better evidence than the address being familiar.
        val result = testSubject.classify(
            evidenceOf("auto-submitted" to "auto-generated", from = "orders@shop.com", hasCorresponded = true),
        )

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
    }

    @Test
    fun `a mailing list should stay a newsletter even for a known correspondent`() {
        val result = testSubject.classify(
            evidenceOf("list-id" to "<dev.example.org>", from = "sam@example.com", hasCorresponded = true),
        )

        assertThat(result.messageClass).isEqualTo(MessageClass.NEWSLETTER)
    }

    @Test
    fun `no-reply sender should mean notification`() {
        val result = testSubject.classify(evidenceOf(from = "no-reply@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
        assertThat(result.signal).isEqualTo(ClassificationSignal.NO_REPLY_SENDER)
    }

    @Test
    fun `no-reply sender should be recognised with a qualifier`() {
        val result = testSubject.classify(evidenceOf(from = "jira.noreply@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
    }

    @Test
    fun `notifications-noreply style sender should be recognised`() {
        // The shape a large social network uses for its notification mail.
        val result = testSubject.classify(evidenceOf(from = "notifications-noreply@social.example"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
    }

    @Test
    fun `a marker buried between other words should be recognised`() {
        // A shape the earlier whole-word match missed, because the marker sits after a hyphen rather than
        // at the start of the local part.
        val result = testSubject.classify(evidenceOf(from = "X-brokerAlerts-DoNotReply@broker.example"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
        assertThat(result.signal).isEqualTo(ClassificationSignal.NO_REPLY_SENDER)
    }

    @Test
    fun `a hyphenated marker should be recognised`() {
        val result = testSubject.classify(evidenceOf(from = "do-not-reply@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.NOTIFICATION)
    }

    @Test
    fun `a person whose name merely contains a marker word should not be a notification`() {
        // "bounce" is a marker; "Bounce Fitness" is a company someone works at.
        val result = testSubject.classify(evidenceOf(from = "morgan.bouncer@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    @Test
    fun `a person whose address merely contains those letters should not be a notification`() {
        // The failure mode that matters: a human misfiled by a substring match.
        val result = testSubject.classify(evidenceOf(from = "noreplacement@example.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    @Test
    fun `a person at a domain containing those letters should not be a notification`() {
        val result = testSubject.classify(evidenceOf(from = "sarah@noreply-agency.com"))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    @Test
    fun `a bulk header should outrank a no-reply sender`() {
        val result = testSubject.classify(
            evidenceOf("list-unsubscribe" to "<https://example.com/u/1>", from = "noreply@example.com"),
        )

        assertThat(result.signal).isEqualTo(ClassificationSignal.BULK_HEADER)
    }

    @Test
    fun `blank header values should be ignored`() {
        val result = testSubject.classify(evidenceOf("list-unsubscribe" to ""))

        assertThat(result.messageClass).isEqualTo(MessageClass.UNKNOWN)
    }

    private fun evidenceOf(
        vararg headers: Pair<String, String>,
        from: String? = null,
        isKnownContact: Boolean = false,
        hasCorresponded: Boolean = false,
    ) = MessageEvidence(
        headers = headers.groupBy({ it.first }, { it.second }),
        fromAddress = from,
        recipientCount = 1,
        isKnownContact = isKnownContact,
        hasCorresponded = hasCorresponded,
    )
}
