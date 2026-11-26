package net.thunderbird.feature.todaysummary.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.feature.todaysummary.TodaySummary
import net.thunderbird.feature.todaysummary.TodaySummaryDestinations
import net.thunderbird.feature.todaysummary.TodaySummaryJob
import net.thunderbird.feature.todaysummary.TodaySummarySource
import net.thunderbird.feature.todaysummary.WaitingMessage

private const val LOG_TAG = "TodaySummary"
private const val CHANNEL_ID = "today_summary"
private const val NOTIFICATION_ID = 0x7D51
private const val REQUEST_OPEN = 1
private const val REQUEST_PACKAGES = 2

/**
 * When a sign-in mail starts counting as overnight: 8 PM the evening before the summary.
 */
private const val OVERNIGHT_STARTS_HOUR = 20
private val OVERNIGHT_STARTS = LocalTime.of(OVERNIGHT_STARTS_HOUR, 0)

/**
 * The most names a line of the summary lists before saying how many more there are.
 */
internal const val MAX_NAMES = 3

/**
 * The most lines the expanded notification shows; Android does not show more.
 */
internal const val MAX_LINES = 5

internal class DefaultTodaySummaryJob(
    private val context: Context,
    private val source: TodaySummarySource,
    private val destinations: TodaySummaryDestinations,
    private val clock: Clock,
    private val logger: Logger,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : TodaySummaryJob {

    override suspend fun showSummary() {
        val now = now()
        val startOfToday = now.toLocalDate().atStartOfDay(now.zone)
        val overnightSince = startOfToday.minusDays(1).with(OVERNIGHT_STARTS)

        val summary = source.summary(
            startOfToday = startOfToday.toInstant().toEpochMilli(),
            overnightSince = overnightSince.toInstant().toEpochMilli(),
        )
        if (summary.isEmpty) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } else {
            notify(summary)
        }
    }

    override fun millisUntilNext(time: String): Long = millisUntilNext(now(), time)

    private fun now(): ZonedDateTime = ZonedDateTime.ofInstant(clock.now().toJavaInstant(), zone())

    private fun notify(summary: TodaySummary) {
        // Checked here, beside the call that needs it, where lint can see it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.todaysummary_channel))
                .setDescription(context.getString(R.string.todaysummary_channel_description))
                .build(),
        )

        val (shownWaiting, moreWaiting) = waitingToShow(summary)
        val lines = shownWaiting.map { messageLine(it) } + otherLines(summary)
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        if (moreWaiting > 0) {
            style.setSummaryText(
                context.resources.getQuantityString(R.plurals.todaysummary_more_waiting, moreWaiting, moreWaiting),
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.todaysummary_ic_notification)
            .setContentTitle(headline(summary))
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(pendingIntent(REQUEST_OPEN, openIntent(summary)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (summary.parcels.isNotEmpty() && tapTarget(summary) != TapTarget.PACKAGES) {
            builder.addAction(
                0,
                context.getString(R.string.todaysummary_open_packages),
                pendingIntent(REQUEST_PACKAGES, destinations.packages()),
            )
        }

        post(manager, builder.build())
    }

    /**
     * Posts the summary. Only called from [notify], after it has checked that notifications may be posted.
     */
    @SuppressLint("MissingPermission")
    private fun post(manager: NotificationManagerCompat, notification: Notification) {
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications turned off for the app since the check.
            logger.warn(LOG_TAG, e) { "Could not show the summary of the day" }
        }
    }

    /**
     * The title: how much of each kind there is, e.g. "2 messages waiting on you · 1 parcel today".
     */
    private fun headline(summary: TodaySummary): String {
        fun quantity(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

        return buildList {
            if (summary.waiting.isNotEmpty()) {
                add(quantity(R.plurals.todaysummary_headline_waiting, summary.waiting.size))
            }
            if (summary.parcels.isNotEmpty()) {
                add(quantity(R.plurals.todaysummary_headline_parcels, summary.parcels.size))
            }
            if (summary.codeCount > 0) {
                add(quantity(R.plurals.todaysummary_headline_codes, summary.codeCount))
            }
        }.joinToString(" · ")
    }

    /**
     * A waiting message as a mail app lists it in a notification: the sender in bold, then the subject.
     */
    private fun messageLine(message: WaitingMessage): CharSequence {
        val subject = message.subject ?: context.getString(R.string.todaysummary_no_subject)
        if (message.sender.isEmpty()) return subject
        return SpannableStringBuilder()
            .append(message.sender, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append("  ")
            .append(subject)
    }

    private fun otherLines(summary: TodaySummary): List<CharSequence> = buildList {
        if (summary.parcels.isNotEmpty()) {
            add(context.getString(R.string.todaysummary_parcels, names(summary.parcels, summary.parcels.size)))
        }
        if (summary.codeCount > 0) {
            add(
                context.resources.getQuantityString(
                    R.plurals.todaysummary_codes,
                    summary.codeCount,
                    summary.codeCount,
                    names(summary.codeSenders, summary.codeSenders.size),
                ),
            )
        }
    }

    private fun openIntent(summary: TodaySummary): Intent = when (tapTarget(summary)) {
        TapTarget.MESSAGE -> destinations.message(summary.waiting.single())
        TapTarget.PACKAGES -> destinations.packages()
        TapTarget.INBOX -> destinations.inbox()
    }

    private fun names(names: List<String>, total: Int): String {
        val (shown, more) = shortList(names, total)
        val listed = shown.joinToString(", ")
        return if (more > 0) context.getString(R.string.todaysummary_and_more, listed, more) else listed
    }

    private fun pendingIntent(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * The waiting messages that get a line of their own, most recent first, and how many are left over. They share the
 * [MAX_LINES] with the lines for parcels and sign-in codes.
 */
internal fun waitingToShow(summary: TodaySummary): Pair<List<WaitingMessage>, Int> {
    val otherLines = (if (summary.parcels.isNotEmpty()) 1 else 0) + (if (summary.codeCount > 0) 1 else 0)
    val shown = summary.waiting.take(MAX_LINES - otherLines)
    return shown to summary.waiting.size - shown.size
}

internal enum class TapTarget { MESSAGE, PACKAGES, INBOX }

/**
 * Where tapping the summary leads: the one message waiting, straight to it; parcels alone, to Packages; otherwise the
 * inbox, where the messages waiting are.
 */
internal fun tapTarget(summary: TodaySummary): TapTarget = when {
    summary.waiting.size == 1 -> TapTarget.MESSAGE
    summary.waiting.isEmpty() && summary.parcels.isNotEmpty() && summary.codeCount == 0 -> TapTarget.PACKAGES
    else -> TapTarget.INBOX
}

/**
 * The first [MAX_NAMES] of [names], and how many of [total] are left over.
 */
internal fun shortList(names: List<String>, total: Int): Pair<List<String>, Int> {
    val shown = names.take(MAX_NAMES)
    return shown to (total - shown.size).coerceAtLeast(0)
}

/**
 * How long from [now] until [time] next comes round: later today, or else tomorrow. A time that cannot be read counts
 * as the default, half past seven.
 */
internal fun millisUntilNext(now: ZonedDateTime, time: String): Long {
    val at = try {
        LocalTime.parse(time.padStart(5, '0'))
    } catch (_: DateTimeParseException) {
        LocalTime.of(7, 30)
    }
    var next = now.with(at).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
}
