package net.thunderbird.feature.plusaddress.internal

import assertk.all
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import net.thunderbird.feature.plusaddress.PlusAddressMailbox
import net.thunderbird.feature.plusaddress.PlusAddressedMail

private const val READER = "reader@mail.example"
private const val SHOP_ADDRESS = "reader+shop@mail.example"
private const val DAY = 24 * 60 * 60 * 1000L

private val NO_DECISIONS = PlusAddressDecisionSnapshot(allowedSenders = emptyMap(), ignored = emptySet())

class PlusAddressAnalysisTest {

    @Test
    fun `the sender a tag names should own the address`() {
        val testSubject = PlusAddressAnalysis

        val observations = testSubject.observeWith(
            mailbox(
                mail(from = "news@mail.other.example", receivedAt = 1 * DAY),
                mail(from = "orders@shop.example", receivedAt = 2 * DAY),
            ),
        )

        assertThat(observations[SHOP_ADDRESS]).isNotNull().all {
            prop(PlusAddressObservation::owners).containsOnly("shop.example")
            prop(PlusAddressObservation::ownerSource).isEqualTo(OwnerSource.TAG)
        }
    }

    @Test
    fun `without a named sender the first verified sender should own the address`() {
        val testSubject = PlusAddressAnalysis

        val observations = testSubject.observeWith(
            mailbox(
                mail(to = "reader+signup@mail.example", from = "a@first.example", receivedAt = 1 * DAY),
                mail(
                    to = "reader+signup@mail.example",
                    from = "b@verified.example",
                    receivedAt = 2 * DAY,
                    dmarc = true,
                ),
                mail(to = "reader+signup@mail.example", from = "c@later.example", receivedAt = 3 * DAY, dmarc = true),
            ),
        )

        assertThat(observations["reader+signup@mail.example"]).isNotNull().all {
            prop(PlusAddressObservation::owners).containsOnly("verified.example")
            prop(PlusAddressObservation::ownerSource).isEqualTo(OwnerSource.FIRST_VERIFIED_SENDER)
        }
    }

    @Test
    fun `mail in spam should never decide who owns the address`() {
        val testSubject = PlusAddressAnalysis

        val observations = testSubject.observeWith(
            mailbox(
                mail(
                    to = "reader+signup@mail.example",
                    from = "x@spammer.example",
                    receivedAt = 1 * DAY,
                    dmarc = true,
                    spam = true,
                ),
                mail(to = "reader+signup@mail.example", from = "y@site.example", receivedAt = 2 * DAY, dmarc = true),
            ),
        )

        assertThat(observations["reader+signup@mail.example"]).isNotNull()
            .prop(PlusAddressObservation::owners).containsOnly("site.example")
    }

    @Test
    fun `senders other than the owner should be listed as having the address`() {
        val observation = PlusAddressAnalysis.observeWith(
            mailbox(
                mail(from = "orders@shop.example", receivedAt = 1 * DAY),
                mail(from = "deals@broker.example", receivedAt = 2 * DAY, spam = true),
                mail(from = "more@broker.example", receivedAt = 3 * DAY),
            ),
        ).getValue(SHOP_ADDRESS)

        val testSubject = PlusAddressAnalysis.record(SHOP_ADDRESS, observation, NO_DECISIONS)

        assertThat(testSubject.isLeaked).isTrue()
        assertThat(testSubject.otherSenders).containsExactly(OtherSender("broker.example", 2, 3 * DAY))
        assertThat(testSubject.messageCount).isEqualTo(3)
    }

    @Test
    fun `a sender the reader said is fine should count as given to`() {
        val observation = PlusAddressAnalysis.observeWith(
            mailbox(
                mail(from = "orders@shop.example", receivedAt = 1 * DAY),
                mail(from = "deals@partner.example", receivedAt = 2 * DAY),
            ),
        ).getValue(SHOP_ADDRESS)
        val decisions = NO_DECISIONS.copy(allowedSenders = mapOf(SHOP_ADDRESS to setOf("partner.example")))

        val testSubject = PlusAddressAnalysis.record(SHOP_ADDRESS, observation, decisions)

        assertThat(testSubject.isLeaked).isFalse()
        assertThat(testSubject.givenTo).containsOnly("shop.example", "partner.example")
    }

    @Test
    fun `an address with no known owner should not be reported as leaked`() {
        val observation = PlusAddressAnalysis.observeWith(
            mailbox(mail(to = "reader+signup@mail.example", from = "a@site.example", receivedAt = 1 * DAY)),
        ).getValue("reader+signup@mail.example")

        val testSubject = PlusAddressAnalysis.record("reader+signup@mail.example", observation, NO_DECISIONS)

        assertThat(testSubject.ownerSource).isEqualTo(OwnerSource.UNKNOWN)
        assertThat(testSubject.isLeaked).isFalse()
    }

    @Test
    fun `mail the reader sent and plus addresses of other people should be left out`() {
        val testSubject = PlusAddressAnalysis

        val observations = testSubject.observeWith(
            mailbox(
                mail(from = READER, receivedAt = 1 * DAY),
                mail(to = "friend+shop@mail.example", from = "orders@shop.example", receivedAt = 2 * DAY),
            ),
        )

        assertThat(observations).isEmpty()
    }

    @Test
    fun `a dotted spelling of a dot-insensitive mailbox should be the same address`() {
        val readers = ReaderAddresses(listOf("first.last@gmail.com"))

        val testSubject = readers.plusAddressOf("firstlast+shop@gmail.com")

        assertThat(testSubject).isNotNull().prop(PlusAddress::key).isEqualTo("firstlast+shop@gmail.com")
        assertThat(readers.plusAddressOf("first.last+shop@GMAIL.com")?.key).isEqualTo("firstlast+shop@gmail.com")
        assertThat(ReaderAddresses(listOf(READER)).plusAddressOf("reader+@mail.example")).isNull()
    }

    @Test
    fun `tags should name domains by their name label`() {
        assertThat(tagNamesDomain("shop", "shop.example")).isTrue()
        assertThat(tagNamesDomain("exampleshop", "shop.example")).isTrue()
        assertThat(tagNamesDomain("Shop-Example", "shopexample.co.uk")).isTrue()
        assertThat(tagNamesDomain("tv", "tvguide.example")).isFalse()
        assertThat(tagNamesDomain("shop", "broker.example")).isFalse()
    }

    private fun PlusAddressAnalysis.observeWith(mailbox: PlusAddressMailbox) = observe(mailbox, TestRegistrableDomains)

    private fun mailbox(vararg mail: PlusAddressedMail) = PlusAddressMailbox(setOf(READER), mail.toList())

    @Suppress("LongParameterList")
    private fun mail(
        to: String = SHOP_ADDRESS,
        from: String,
        receivedAt: Long,
        dmarc: Boolean = false,
        spam: Boolean = false,
    ) = PlusAddressedMail(
        recipientAddresses = listOf(to),
        senderAddress = from,
        receivedAt = receivedAt,
        isSenderAuthenticated = dmarc,
        isInSpamOrTrash = spam,
    )
}
