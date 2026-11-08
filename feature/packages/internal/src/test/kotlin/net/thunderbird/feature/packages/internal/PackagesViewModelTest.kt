package net.thunderbird.feature.packages.internal

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
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
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail
import net.thunderbird.feature.packages.ShippingMailSource
import net.thunderbird.feature.packages.internal.PackagesContract.Effect
import net.thunderbird.feature.packages.internal.PackagesContract.Event
import net.thunderbird.feature.packages.internal.carrier.LiveResult
import net.thunderbird.feature.packages.internal.carrier.LiveStatus
import net.thunderbird.feature.packages.internal.carrier.LiveTracker

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 100 * DAY

@OptIn(ExperimentalTime::class)
class PackagesViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val ups = ParcelNumber(ParcelCarrier.UPS, "1Z999AA10123456784", "https://ups.example/1Z")
    private val usps = ParcelNumber(ParcelCarrier.USPS, "9400100000000000000013", "https://usps.example/94")
    private val mailSource = FakeMailSource(
        listOf(
            mail("1", NOW - 2 * DAY, "Your order has shipped", listOf(ups, usps)),
            mail("2", NOW - DAY, "Delivered: your order", listOf(usps)),
        ),
    )
    private val marks = FakeMarks()
    private val liveTracker = FakeLiveTracker()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
    }
    private val testSubject by lazy { PackagesViewModel(mailSource, marks, liveTracker, clock) }

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `loading should split parcels into on the way and delivered`() {
        testSubject.event(Event.LoadParcels)

        val state = testSubject.state.value
        assertThat(state.onTheWay.map { it.number }).containsExactly(ups.number)
        assertThat(state.delivered.map { it.number }).containsExactly(usps.number)
    }

    @Test
    fun `carriers with credentials should be asked about parcels still on the way only`() {
        liveTracker.carriers = setOf(ParcelCarrier.UPS, ParcelCarrier.USPS)
        liveTracker.result = LiveResult.Found(LiveStatus(ParcelStatus.OUT_FOR_DELIVERY, "Out For Delivery"))

        testSubject.event(Event.LoadParcels)

        assertThat(liveTracker.asked).containsExactly(ups.number)
        assertThat(testSubject.state.value.statusOf(testSubject.state.value.parcels.first { it.number == ups.number }))
            .isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
    }

    @Test
    fun `without credentials no carrier should be asked`() {
        testSubject.event(Event.LoadParcels)

        assertThat(liveTracker.asked).isEmpty()
    }

    @Test
    fun `marking a parcel delivered should move it and be remembered`() {
        testSubject.event(Event.LoadParcels)

        testSubject.event(Event.MarkDelivered(ups.number, isDelivered = true))

        assertThat(marks.delivered).isEqualTo(setOf(ups.number))
        assertThat(testSubject.state.value.onTheWay).isEmpty()
    }

    @Test
    fun `back should close an open parcel before leaving the screen`() = runTest {
        testSubject.event(Event.LoadParcels)
        testSubject.event(Event.ParcelClicked(ups.number))

        testSubject.event(Event.BackClicked)
        assertThat(testSubject.state.value.selectedNumber).isNull()

        testSubject.effect.test {
            testSubject.event(Event.BackClicked)
            assertThat(awaitItem()).isEqualTo(Effect.NavigateBack)
        }
    }

    @Test
    fun `the summary's actions should reach the mail source`() {
        testSubject.event(Event.LoadParcels)

        testSubject.event(Event.SearchAllMail(ups.number))
        testSubject.event(Event.OpenMail("1"))

        assertThat(mailSource.calls).containsExactly("search ${ups.number}", "open 1")
    }

    @Test
    fun `opening should download the shipping mail not on the device without asking`() {
        mailSource.notDownloaded = 3

        testSubject.event(Event.LoadParcels)

        assertThat(mailSource.calls).containsExactly("download since ${NOW - SCAN_WINDOW_DAYS * DAY}")
        assertThat(testSubject.state.value.mailsNotDownloaded).isEqualTo(0)
        assertThat(testSubject.state.value.download).isNull()
    }

    @Test
    fun `mail that fails to download on opening should be left to the reader's button`() {
        mailSource.notDownloaded = 3
        mailSource.isDownloadFailing = true

        testSubject.event(Event.LoadParcels)
        testSubject.event(Event.Refresh)

        assertThat(mailSource.calls).containsExactly("download since ${NOW - SCAN_WINDOW_DAYS * DAY}")
        assertThat(testSubject.state.value.mailsNotDownloaded).isEqualTo(3)
    }

    private fun mail(key: String, date: Long, subject: String, parcels: List<ParcelNumber>) =
        ShippingMail(key, date, "Shop", subject, parcels, subject)

    private class FakeMailSource(private val mails: List<ShippingMail>) : ShippingMailSource {
        val calls = mutableListOf<String>()

        var notDownloaded = 0
        var isDownloadFailing = false

        override suspend fun shippingMails(since: Long) = mails
        override suspend fun notDownloadedCount(since: Long) = notDownloaded
        override suspend fun downloadMails(since: Long, onProgress: (Int, Int) -> Unit): Int {
            calls += "download since $since"
            if (isDownloadFailing) return 0
            return notDownloaded.also { notDownloaded = 0 }
        }

        override fun openMail(key: String) {
            calls += "open $key"
        }

        override fun searchAllMail(query: String) {
            calls += "search $query"
        }

        override fun openTrackingPage(url: String) {
            calls += "track $url"
        }
    }

    private class FakeMarks : ParcelMarks {
        val delivered = mutableSetOf<String>()
        private val removed = mutableSetOf<String>()

        override fun markedDelivered(): Set<String> = delivered
        override fun removed(): Set<String> = removed

        override fun setMarkedDelivered(number: String, isDelivered: Boolean) {
            if (isDelivered) delivered += number else delivered -= number
        }

        override fun remove(number: String) {
            removed += number
        }
    }

    private class FakeLiveTracker : LiveTracker {
        var carriers: Set<ParcelCarrier> = emptySet()
        var result: LiveResult = LiveResult.Unavailable
        val asked = mutableListOf<String>()

        override fun carriers(): Set<ParcelCarrier> = carriers

        override suspend fun track(parcel: ParcelNumber): LiveResult? {
            if (parcel.carrier !in carriers) return null
            asked += parcel.number
            return result
        }
    }
}
