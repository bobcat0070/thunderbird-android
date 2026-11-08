package net.thunderbird.feature.trips.internal

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.thunderbird.feature.trips.TravelMailDownloader
import net.thunderbird.feature.trips.TravelMailSource

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

/**
 * How many mails one run downloads at most, newest first. A year of mail can hold hundreds of confirmations the first
 * time; the rest follow on later runs.
 */
internal const val MAX_DOWNLOADS_PER_RUN = 100

/**
 * Downloads the mail Trips would read, [TRIPS_SCAN_DAYS] of it, that is not on the device yet.
 */
@OptIn(ExperimentalTime::class)
internal class DefaultTravelMailDownloader(
    private val mailSource: TravelMailSource,
    private val senders: TravelSenderStore,
    private val clock: Clock,
) : TravelMailDownloader {
    override suspend fun downloadRecent(): Int {
        val keys = mailSource.candidates(
            since = clock.now().toEpochMilliseconds() - TRIPS_SCAN_DAYS * MILLIS_PER_DAY,
            senderDomains = senders.listed().map { it.domain }.toSet(),
            markedMessageIds = senders.markedMessageIds(),
        )
            .filterNot { header -> header.isDownloaded }
            .sortedByDescending { header -> header.date }
            .take(MAX_DOWNLOADS_PER_RUN)
            .map { header -> header.key }
        if (keys.isEmpty()) return 0

        return mailSource.download(keys) { _, _ -> }
    }
}
