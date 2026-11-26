package net.thunderbird.feature.todaysummary

import android.content.Intent

/**
 * What the morning summary says.
 *
 * @param parcels who each parcel out for delivery today is from.
 * @param waiting the unread messages from people waiting on the reader, most recent first.
 * @param codeCount how many sign-in and verification mails came overnight.
 * @param codeSenders who they are from, each once.
 */
data class TodaySummary(
    val parcels: List<String>,
    val waiting: List<WaitingMessage>,
    val codeCount: Int,
    val codeSenders: List<String>,
) {
    val isEmpty: Boolean
        get() = parcels.isEmpty() && waiting.isEmpty() && codeCount == 0
}

/**
 * An unread message from a person, and where it is stored so the summary can open it.
 *
 * @param sender the sender's name, or their address when the message gives no name.
 * @param subject the subject, or `null` when the message has none.
 */
data class WaitingMessage(
    val sender: String,
    val subject: String?,
    val accountId: String,
    val folderId: Long,
    val messageServerId: String,
)

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
    /** The inbox of every account, where the people waiting are. */
    fun inbox(): Intent

    /** One waiting message, opened on its own. */
    fun message(message: WaitingMessage): Intent

    /** Packages, where the parcels are. */
    fun packages(): Intent
}
