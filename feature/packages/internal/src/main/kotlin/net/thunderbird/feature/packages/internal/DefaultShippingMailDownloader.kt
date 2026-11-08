package net.thunderbird.feature.packages.internal

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.thunderbird.feature.packages.ShippingMailDownloader
import net.thunderbird.feature.packages.ShippingMailSource

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

/**
 * Downloads the shipping mail of the window Packages shows, [SCAN_WINDOW_DAYS], that is not on the device yet.
 */
@OptIn(ExperimentalTime::class)
internal class DefaultShippingMailDownloader(
    private val mailSource: ShippingMailSource,
    private val clock: Clock,
) : ShippingMailDownloader {
    override suspend fun downloadRecent(): Int {
        val since = clock.now().toEpochMilliseconds() - SCAN_WINDOW_DAYS * MILLIS_PER_DAY
        if (mailSource.notDownloadedCount(since) == 0) return 0

        return mailSource.downloadMails(since) { _, _ -> }
    }
}
