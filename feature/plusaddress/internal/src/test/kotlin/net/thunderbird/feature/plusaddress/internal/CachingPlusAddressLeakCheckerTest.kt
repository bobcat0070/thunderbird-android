package net.thunderbird.feature.plusaddress.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.thunderbird.core.testing.TestClock
import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import net.thunderbird.feature.plusaddress.PlusAddressLeak
import net.thunderbird.feature.plusaddress.PlusAddressMailSource
import net.thunderbird.feature.plusaddress.PlusAddressMailbox
import net.thunderbird.feature.plusaddress.PlusAddressedMail

private const val READER = "reader@mail.example"
private const val SHOP_ADDRESS = "reader+shop@mail.example"

class CachingPlusAddressLeakCheckerTest {
    private val clock = TestClock(Instant.parse("2026-11-20T12:00:00Z"))
    private var mailbox = PlusAddressMailbox(
        readerAddresses = setOf(READER),
        mail = listOf(mail(from = "orders@shop.example")),
    )
    private var readings = 0
    private val source = PlusAddressMailSource {
        readings++
        mailbox
    }
    private val decisions = FakePlusAddressDecisionStore()
    private val testSubject = CachingPlusAddressLeakChecker(source, decisions, clock, TestRegistrableDomains)

    @Test
    fun `mail on a plus address from a sender it was not given to should be a leak`() {
        val result = testSubject.check(listOf("Reader+Shop@mail.example"), "deals@broker.example")

        assertThat(result).isEqualTo(
            PlusAddressLeak(
                plusAddress = SHOP_ADDRESS,
                givenTo = setOf("shop.example"),
                senderDomain = "broker.example",
            ),
        )
    }

    @Test
    fun `mail from the owner, from the reader, or on no plus address should not be a leak`() {
        assertThat(testSubject.check(listOf(SHOP_ADDRESS), "news@mail.shop.example")).isNull()
        assertThat(testSubject.check(listOf(SHOP_ADDRESS), READER)).isNull()
        assertThat(testSubject.check(listOf(READER), "deals@broker.example")).isNull()
        assertThat(testSubject.check(listOf("reader+unseen@mail.example"), "deals@broker.example")).isNull()
    }

    @Test
    fun `a sender the reader accepted should stop being a leak at once`() {
        testSubject.check(listOf(SHOP_ADDRESS), "deals@broker.example")

        decisions.allowSender(SHOP_ADDRESS, "broker.example")

        assertThat(testSubject.check(listOf(SHOP_ADDRESS), "deals@broker.example")).isNull()
        assertThat(readings).isEqualTo(1)
    }

    @Test
    fun `the plus addresses a sender was given should be found`() {
        assertThat(testSubject.addressesGivenTo("security@mail.shop.example")).containsExactly(SHOP_ADDRESS)
        assertThat(testSubject.addressesGivenTo("deals@broker.example")).isEmpty()
    }

    @Test
    fun `an address the reader stopped checking should not be a leak`() {
        decisions.setIgnored(SHOP_ADDRESS, isIgnored = true)

        assertThat(testSubject.check(listOf(SHOP_ADDRESS), "deals@broker.example")).isNull()
    }

    @Test
    fun `stored mail should be read once for many checks and again after a while`() {
        repeat(5) { testSubject.check(listOf(SHOP_ADDRESS), "deals@broker.example") }
        assertThat(readings).isEqualTo(1)

        clock.advanceTimeBy(31.minutes)
        testSubject.check(listOf(SHOP_ADDRESS), "deals@broker.example")

        assertThat(readings).isEqualTo(2)
    }

    @Test
    fun `mail without a plus in its recipients should not read stored mail`() {
        testSubject.check(listOf(READER), "deals@broker.example")

        assertThat(readings).isEqualTo(0)
    }

    @Test
    fun `records should list leaked addresses first`() {
        mailbox = mailbox.copy(
            mail = listOf(
                mail(to = "reader+quiet@mail.example", from = "a@quiet.example", receivedAt = 3_000),
                mail(from = "orders@shop.example", receivedAt = 1_000),
                mail(from = "deals@broker.example", receivedAt = 2_000),
            ),
        )

        val result = testSubject.records(refresh = true)

        assertThat(result.map { it.key }).isEqualTo(listOf(SHOP_ADDRESS, "reader+quiet@mail.example"))
        assertThat(result.first().otherSenders.map { it.domain }).containsOnly("broker.example")
    }

    private fun mail(to: String = SHOP_ADDRESS, from: String, receivedAt: Long = 0) = PlusAddressedMail(
        recipientAddresses = listOf(to),
        senderAddress = from,
        receivedAt = receivedAt,
        isSenderAuthenticated = false,
        isInSpamOrTrash = false,
    )
}

internal class FakePlusAddressDecisionStore : PlusAddressDecisionStore {
    private val allowed = mutableMapOf<String, MutableSet<String>>()
    private val ignored = mutableSetOf<String>()

    override fun decisions() = PlusAddressDecisionSnapshot(allowedSenders = allowed.toMap(), ignored = ignored.toSet())

    override fun allowSender(plusAddress: String, domain: String) {
        allowed.getOrPut(plusAddress) { mutableSetOf() }.add(domain)
    }

    override fun setIgnored(plusAddress: String, isIgnored: Boolean) {
        if (isIgnored) ignored.add(plusAddress) else ignored.remove(plusAddress)
    }
}
