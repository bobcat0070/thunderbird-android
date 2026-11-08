package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.packages.ShippingMail
import net.thunderbird.feature.packages.ShippingMailSource

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1000 * DAY

@OptIn(ExperimentalTime::class)
class DefaultShippingMailDownloaderTest {
    private val mailSource = FakeMailSource()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
    }
    private val testSubject = DefaultShippingMailDownloader(mailSource, clock)

    @Test
    fun `the shipping mail Packages shows should be downloaded when some is not on the device`() = runTest {
        mailSource.notDownloaded = 4

        val result = testSubject.downloadRecent()

        assertThat(result).isEqualTo(4)
        assertThat(mailSource.downloadedSince).containsExactly(NOW - SCAN_WINDOW_DAYS * DAY)
    }

    @Test
    fun `nothing should be downloaded when all shipping mail is on the device`() = runTest {
        val result = testSubject.downloadRecent()

        assertThat(result).isEqualTo(0)
        assertThat(mailSource.downloadedSince).isEmpty()
    }

    private class FakeMailSource : ShippingMailSource {
        var notDownloaded = 0
        val downloadedSince = mutableListOf<Long>()

        override suspend fun shippingMails(since: Long): List<ShippingMail> = emptyList()
        override suspend fun notDownloadedCount(since: Long) = notDownloaded

        override suspend fun downloadMails(since: Long, onProgress: (done: Int, total: Int) -> Unit): Int {
            downloadedSince += since
            return notDownloaded
        }

        override fun openMail(key: String) = Unit
        override fun searchAllMail(query: String) = Unit
        override fun openTrackingPage(url: String) = Unit
    }
}
