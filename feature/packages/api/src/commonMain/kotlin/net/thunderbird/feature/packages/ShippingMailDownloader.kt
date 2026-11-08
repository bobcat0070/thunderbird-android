package net.thunderbird.feature.packages

/**
 * Downloads the bodies of the recent shipping mail known only by its envelope, as Microsoft 365 mail is until it is
 * opened, so Packages can read its tracking numbers without the reader asking.
 */
fun interface ShippingMailDownloader {
    /**
     * @return how many mails were downloaded.
     */
    suspend fun downloadRecent(): Int
}
