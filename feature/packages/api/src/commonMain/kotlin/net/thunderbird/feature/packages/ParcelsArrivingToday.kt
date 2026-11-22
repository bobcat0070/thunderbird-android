package net.thunderbird.feature.packages

/**
 * The parcels coming today, for a summary of the day.
 */
fun interface ParcelsArrivingToday {
    /**
     * @param startOfToday the start of the reader's day, in milliseconds since the epoch.
     * @return who each parcel out for delivery today is from - the shop that mailed about it, or else the subject of
     *   its first mail - for every parcel whose newest mail, sent today, says it is out for delivery.
     */
    suspend fun arrivingToday(startOfToday: Long): List<String>
}
