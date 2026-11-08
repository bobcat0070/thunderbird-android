package net.thunderbird.feature.trips

/**
 * Downloads the bodies of the recent mail that may confirm a booking and is known only by its envelope, as Microsoft
 * 365 mail is until it is opened, so Trips can read the bookings without the reader asking.
 */
fun interface TravelMailDownloader {
    /**
     * @return how many mails were downloaded.
     */
    suspend fun downloadRecent(): Int
}
