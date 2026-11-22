package net.thunderbird.feature.todaysummary.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

private const val LOG_TAG = "TodaySummary"
private const val CHANNEL_ID = "today_summary"
private const val NOTIFICATION_ID = 0x7D51
private const val REQUEST_INBOX = 1
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

        val lines = lines(summary)
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.todaysummary_ic_notification)
            .setContentTitle(context.getString(R.string.todaysummary_title))
            .setContentText(lines.joinToString(" · "))
            .setStyle(style)
            .setContentIntent(pendingIntent(REQUEST_INBOX, destinations.inbox()))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (summary.parcels.isNotEmpty()) {
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

    private fun lines(summary: TodaySummary): List<String> = buildList {
        if (summary.parcels.isNotEmpty()) {
            add(context.getString(R.string.todaysummary_parcels, names(summary.parcels, summary.parcels.size)))
        }
        if (summary.waitingCount > 0) {
            add(
                context.resources.getQuantityString(
                    R.plurals.todaysummary_waiting,
                    summary.waitingCount,
                    summary.waitingCount,
                    names(summary.waitingSenders, summary.waitingSenders.size),
                ),
            )
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

    private fun names(names: List<String>, total: Int): String {
        val (shown, more) = shortList(names, total)
        val listed = shown.joinToString(", ")
        return if (more > 0) context.getString(R.string.todaysummary_and_more, listed, more) else listed
    }

    private fun pendingIntent(requestCode: Int, intent: android.content.Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
