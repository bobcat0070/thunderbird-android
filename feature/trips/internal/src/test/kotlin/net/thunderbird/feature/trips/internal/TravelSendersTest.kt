package net.thunderbird.feature.trips.internal

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.single
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.ui.testing.coroutines.MainDispatcherHelper
import net.thunderbird.feature.trips.TravelMailContent
import net.thunderbird.feature.trips.TravelMailHeader
import net.thunderbird.feature.trips.TravelMailSource

private val NOW = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@OptIn(ExperimentalTime::class)
class TravelSendersTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val senders = FakeTravelSenderStore()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
    }

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `a sender at a subdomain of a listed domain should be listed under it`() {
        val domains = setOf("flyfrontier.com", "aa.com")

        assertThat(listedDomainOf("flights@t.flyfrontier.com", domains)).isEqualTo("flyfrontier.com")
        assertThat(listedDomainOf("FLIGHTS@FlyFrontier.com", domains)).isEqualTo("flyfrontier.com")
        assertThat(listedDomainOf("news@notflyfrontier.com", domains)).isNull()
        assertThat(listedDomainOf("someone@baa.com", domains)).isNull()
    }

    @Test
    fun `only plain domain names should be taken`() {
        assertThat(normalizedDomain(" Expedia.COM. ")).isEqualTo("expedia.com")
        assertThat(normalizedDomain("exa mple.com")).isNull()
        assertThat(normalizedDomain("x' OR 1=1 --.com")).isNull()
        assertThat(normalizedDomain("localhost")).isNull()
    }

    @Test
    fun `a sender's domain should be the one its owner registered`() {
        assertThat(registrableDomain("t.flyfrontier.com")).isEqualTo("flyfrontier.com")
        assertThat(registrableDomain("mail.example.co.uk")).isEqualTo("example.co.uk")
    }

    @Test
    fun `a typed domain should be listed and a typed address should list its domain`() {
        val testSubject = TravelSendersViewModel(senders)

        testSubject.event(TravelSendersContract.Event.NewDomainChanged("Expedia.com"))
        testSubject.event(TravelSendersContract.Event.AddClicked)
        testSubject.event(TravelSendersContract.Event.NewDomainChanged("bookings@asrlodging.com"))
        testSubject.event(TravelSendersContract.Event.AddClicked)

        assertThat(testSubject.state.value.senders.map { it.domain }).containsExactly("asrlodging.com", "expedia.com")
        assertThat(testSubject.state.value.newDomain).isEqualTo("")
    }

    @Test
    fun `something that is not a domain should not be listed`() = runTest {
        val testSubject = TravelSendersViewModel(senders)

        testSubject.effect.test {
            testSubject.event(TravelSendersContract.Event.NewDomainChanged("not a domain"))
            testSubject.event(TravelSendersContract.Event.AddClicked)

            assertThat(awaitItem()).isEqualTo(TravelSendersContract.Effect.NotADomain)
        }
        assertThat(senders.listed()).containsExactly()
    }

    @Test
    fun `a marked mail should be read even though its subject is not a booking's`() = runTest {
        senders.setMarked("<weekend@example.com>", isMarked = true)
        val mailSource = FakeTravelMailSource(
            header = TravelMailHeader(
                key = "m1",
                date = NOW,
                senderName = "Jana",
                senderAddress = "jana@example.com",
                subject = "Fwd: our weekend",
                isDownloaded = true,
                isMarked = true,
            ),
            content = TravelMailContent(html = null, text = "Dinner at Zuni on Nov 20, 2026 at 7:30 PM"),
        )
        val testSubject = TripsViewModel(mailSource, ReservationCache(), senders, clock)

        testSubject.state.test {
            testSubject.event(TripsContract.Event.LoadTrips)
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            assertThat(state.trips).single().transform { it.reservations }.single()
                .transform { it.type }.isEqualTo(ReservationType.RESTAURANT)
        }
        assertThat(mailSource.markedMessageIds).isEqualTo(setOf("<weekend@example.com>"))
    }

    @Test
    fun `trips should be read again when the sender list has changed`() {
        val mailSource = FakeTravelMailSource(header = null, content = null)
        val testSubject = TripsViewModel(mailSource, ReservationCache(), senders, clock)

        testSubject.event(TripsContract.Event.LoadTrips)
        testSubject.event(TripsContract.Event.LoadTrips)
        senders.setListed("expedia.com", isListed = true)
        testSubject.event(TripsContract.Event.LoadTrips)

        assertThat(mailSource.calls).isEqualTo(2)
        assertThat(mailSource.senderDomains).isEqualTo(setOf("expedia.com"))
    }
}

private class FakeTravelSenderStore : TravelSenderStore {
    private val domains = mutableSetOf<String>()
    private val marked = mutableSetOf<String>()

    override var version: Int = 0
        private set

    override fun domainOf(address: String): String? = address.substringAfterLast('@').ifEmpty { null }

    override fun isListed(domain: String): Boolean = domain in domains

    override fun setListed(domain: String, isListed: Boolean) {
        if (isListed) domains += domain else domains -= domain
        version++
    }

    override fun isMarked(messageId: String): Boolean = messageId in marked

    override fun setMarked(messageId: String, isMarked: Boolean) {
        if (isMarked) marked += messageId else marked -= messageId
        version++
    }

    override fun listed(): List<ListedSender> =
        domains.sorted().map { ListedSender(domain = it, type = null, isAddedByReader = true) }

    override fun markedMessageIds(): Set<String> = marked.toSet()

    override fun typeOf(address: String): ReservationType? = null

    override fun isListedSender(address: String): Boolean = listedDomainOf(address, domains) != null
}

private class FakeTravelMailSource(
    private val header: TravelMailHeader?,
    private val content: TravelMailContent?,
) : TravelMailSource {
    var calls = 0
    var senderDomains: Set<String> = emptySet()
    var markedMessageIds: Set<String> = emptySet()

    override suspend fun candidates(
        since: Long,
        senderDomains: Set<String>,
        markedMessageIds: Set<String>,
    ): List<TravelMailHeader> {
        calls++
        this.senderDomains = senderDomains
        this.markedMessageIds = markedMessageIds
        return listOfNotNull(header)
    }

    override suspend fun content(key: String): TravelMailContent? = content

    override suspend fun download(keys: List<String>, onProgress: (done: Int, total: Int) -> Unit): Int = 0

    override fun openMail(key: String) = Unit
}
