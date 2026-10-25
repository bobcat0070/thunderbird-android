package net.thunderbird.feature.blocklist.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.core.preference.blocklist.DomainBlocklistSettings
import net.thunderbird.core.preference.blocklist.DomainBlocklistTier

@OptIn(ExperimentalTime::class)
class DefaultBlocklistUpdaterTest {
    private var settings = DomainBlocklistSettings(tier = DomainBlocklistTier.NORMAL, isThreatListEnabled = true)
    private val clock = FakeClock(Instant.parse("2026-10-24T12:00:00Z"))
    private val store = FakeBlocklistStore(clock)
    private val fetcher = FakeBlocklistFetcher()
    private val blocklist = DefaultDomainBlocklist { settings }
    private val testSubject = DefaultBlocklistUpdater(
        blocklist = blocklist,
        settingsProvider = { settings },
        store = store,
        fetcher = fetcher,
        clock = clock,
        logger = TestLogger(),
    )

    @Test
    fun `missing lists should be downloaded, saved and put to use`() = runTest {
        val result = testSubject.update()

        assertThat(result).isTrue()
        assertThat(fetcher.fetched).containsExactly(HagezeiList.NORMAL.url, HagezeiList.THREATS.url)
        assertThat(store.names()).isEqualTo(setOf("multi", "tif.medium"))
        assertThat(blocklist.match("ads.normal.example")).isNotNull()
        assertThat(blocklist.match("bad.threats.example")).isNotNull()
    }

    @Test
    fun `lists downloaded recently should not be fetched again`() = runTest {
        testSubject.update()
        fetcher.fetched.clear()
        clock.now = clock.now + 5.hours

        testSubject.update()

        assertThat(fetcher.fetched).isEmpty()
    }

    @Test
    fun `lists older than a day should be fetched again`() = runTest {
        testSubject.update()
        fetcher.fetched.clear()
        clock.now = clock.now + 21.hours

        testSubject.update()

        assertThat(fetcher.fetched).containsExactly(HagezeiList.NORMAL.url, HagezeiList.THREATS.url)
    }

    @Test
    fun `lists no longer chosen should be deleted and stop matching`() = runTest {
        testSubject.update()

        settings = DomainBlocklistSettings(tier = DomainBlocklistTier.LIGHT, isThreatListEnabled = false)
        testSubject.update()

        assertThat(store.names()).isEqualTo(setOf("light"))
        assertThat(blocklist.match("bad.threats.example")).isNull()
    }

    @Test
    fun `a failed download should keep the list already on the device`() = runTest {
        testSubject.update()
        clock.now = clock.now + 21.hours
        fetcher.failing = true

        val result = testSubject.update()

        assertThat(result).isFalse()
        assertThat(blocklist.match("ads.normal.example")).isNotNull()
    }

    @Test
    fun `turning everything off should download nothing and match nothing`() = runTest {
        settings = DomainBlocklistSettings(tier = DomainBlocklistTier.OFF, isThreatListEnabled = false)

        testSubject.update()

        assertThat(fetcher.fetched).isEmpty()
        assertThat(testSubject.isListMissing()).isFalse()
        assertThat(blocklist.match("ads.normal.example")).isNull()
    }

    @Test
    fun `loading from the device should not use the network`() = runTest {
        testSubject.update()
        fetcher.fetched.clear()
        val restarted = DefaultDomainBlocklist { settings }
        val updater = DefaultBlocklistUpdater(restarted, { settings }, store, fetcher, clock, TestLogger())

        updater.loadFromDevice()

        assertThat(fetcher.fetched).isEmpty()
        assertThat(restarted.match("ads.normal.example")).isNotNull()
    }
}

@OptIn(ExperimentalTime::class)
private class FakeClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}

@OptIn(ExperimentalTime::class)
private class FakeBlocklistStore(private val clock: Clock) : BlocklistStore {
    private val files = mutableMapOf<String, Pair<ByteArray, Instant>>()

    override fun read(name: String): ByteArray? = files[name]?.first
    override fun write(name: String, bytes: ByteArray) {
        files[name] = bytes to clock.now()
    }
    override fun delete(name: String) {
        files.remove(name)
    }
    override fun writtenAt(name: String): Instant? = files[name]?.second
    override fun names(): Set<String> = files.keys.toSet()
}

private class FakeBlocklistFetcher : BlocklistFetcher {
    val fetched = mutableListOf<String>()
    var failing = false

    override suspend fun fetch(url: String): HashedDomainListResult {
        fetched += url
        if (failing) error("offline")
        val domain = when (url) {
            HagezeiList.THREATS.url -> "threats.example"
            HagezeiList.LIGHT.url -> "light.example"
            else -> "normal.example"
        }
        return HashedDomainListResult(HashedDomainList.fromLines(sequenceOf(domain)))
    }
}
