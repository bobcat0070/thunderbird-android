package net.thunderbird.feature.todaysummary

import android.content.Intent

/**
 * What the morning summary says.
 *
 * @param parcels who each parcel out for delivery today is from.
 * @param waitingCount how many unread messages from people are waiting.
 * @param waitingSenders who they are from, most recent first, each once.
 * @param codeCount how many sign-in and verification mails came overnight.
 * @param codeSenders who they are from, each once.
 */
data class TodaySummary(
    val parcels: List<String>,
    val waitingCount: Int,
    val waitingSenders: List<String>,
    val codeCount: Int,
    val codeSenders: List<String>,
) {
    val isEmpty: Boolean
        get() = parcels.isEmpty() && waitingCount == 0 && codeCount == 0
}

/**
 * Gathers the summary from the reader's mail.
 */
fun interface TodaySummarySource {
    /**
     * @param startOfToday the start of the reader's day, in milliseconds since the epoch.
     * @param overnightSince the evening before, from which a sign-in mail counts as overnight.
     */
    suspend fun summary(startOfToday: Long, overnightSince: Long): TodaySummary
}

/**
 * Shows the summary, and says when it is next due. Run by the app's scheduler.
 */
interface TodaySummaryJob {
    /**
     * Gathers the summary and shows it as a notification; nothing is shown when there is nothing to say.
     */
    suspend fun showSummary()

    /**
     * @param time when the summary is shown each day, as "HH:mm" in the phone's time zone.
     * @return how long until it is next shown, in milliseconds: today if that time has not come yet, else tomorrow.
     */
    fun millisUntilNext(time: String): Long
}

/**
 * Where the summary's notification leads. Bound by the app.
 */
interface TodaySummaryDestinations {
    /** The inbox, where the people waiting are. */
    fun inbox(): Intent

    /** Packages, where the parcels are. */
    fun packages(): Intent
}
