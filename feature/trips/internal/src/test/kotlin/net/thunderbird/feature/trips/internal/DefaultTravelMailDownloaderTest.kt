package net.thunderbird.feature.trips.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.trips.TravelMailContent
import net.thunderbird.feature.trips.TravelMailHeader
import net.thunderbird.feature.trips.TravelMailSource

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1000 * DAY

@OptIn(ExperimentalTime::class)
class DefaultTravelMailDownloaderTest {
    private val mailSource = FakeTravelMailSource()
    private val senders = FakeTravelSenderStore()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
    }
    private val testSubject = DefaultTravelMailDownloader(mailSource, senders, clock)

    @Test
    fun `only the mails not on the device should be downloaded, newest first`() = runTest {
        mailSource.headers = listOf(
            header("old", date = NOW - 3 * DAY, isDownloaded = false),
            header("read", date = NOW - 2 * DAY, isDownloaded = true),
            header("new", date = NOW - DAY, isDownloaded = false),
        )

        val result = testSubject.downloadRecent()

        assertThat(result).isEqualTo(2)
        assertThat(mailSource.downloaded).containsExactly("new", "old")
        assertThat(mailSource.askedSince).isEqualTo(NOW - TRIPS_SCAN_DAYS * DAY)
        assertThat(mailSource.askedDomains).containsOnly("flyfrontier.com")
        assertThat(mailSource.askedMarked).containsOnly("<marked@example.com>")
    }

    @Test
    fun `one run should download at most the newest hundred mails`() = runTest {
        mailSource.headers = (1..MAX_DOWNLOADS_PER_RUN + 5).map { index ->
            header("mail$index", date = NOW - index * 60_000L, isDownloaded = false)
        }

        testSubject.downloadRecent()

        assertThat(mailSource.downloaded.size).isEqualTo(MAX_DOWNLOADS_PER_RUN)
        assertThat(mailSource.downloaded.first()).isEqualTo("mail1")
    }

    @Test
    fun `nothing should be downloaded when every mail is on the device`() = runTest {
        mailSource.headers = listOf(header("read", date = NOW - DAY, isDownloaded = true))

        val result = testSubject.downloadRecent()

        assertThat(result).isEqualTo(0)
        assertThat(mailSource.downloaded).isEmpty()
    }

    private fun header(key: String, date: Long, isDownloaded: Boolean) = TravelMailHeader(
        key = key,
        date = date,
        senderName = null,
        senderAddress = null,
        subject = "Your booking is confirmed",
        isDownloaded = isDownloaded,
    )

    private class FakeTravelMailSource : TravelMailSource {
        var headers: List<TravelMailHeader> = emptyList()
        val downloaded = mutableListOf<String>()
        var askedSince: Long? = null
        var askedDomains: Set<String> = emptySet()
        var askedMarked: Set<String> = emptySet()

        override suspend fun candidates(
            since: Long,
            senderDomains: Set<String>,
            markedMessageIds: Set<String>,
        ): List<TravelMailHeader> {
            askedSince = since
            askedDomains = senderDomains
            askedMarked = markedMessageIds
            return headers
        }

        override suspend fun content(key: String): TravelMailContent? = null

        override suspend fun download(keys: List<String>, onProgress: (done: Int, total: Int) -> Unit): Int {
            downloaded += keys
            return keys.size
        }

        override fun openMail(key: String) = Unit
    }

    private class FakeTravelSenderStore : TravelSenderStore {
        override val version: Int = 0

        override fun listed() = listOf(ListedSender("flyfrontier.com", type = null, isAddedByReader = false))
        override fun markedMessageIds() = setOf("<marked@example.com>")
        override fun typeOf(address: String): ReservationType? = null
        override fun isListedSender(address: String) = false
        override fun domainOf(address: String): String? = null
        override fun isListed(domain: String) = false
        override fun setListed(domain: String, isListed: Boolean) = Unit
        override fun isMarked(messageId: String) = false
        override fun setMarked(messageId: String, isMarked: Boolean) = Unit
    }
}
