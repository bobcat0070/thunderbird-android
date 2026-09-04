package com.fsck.k9.backends

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.thunderbird.backend.graph.GraphPushScheduler
import net.thunderbird.core.logging.Logger

private const val ALARM_ACTION = "com.fsck.k9.backends.GRAPH_PUSH_ALARM"
private const val ALARM_SCHEME = "graphpush"
private const val MILLIS_PER_SECOND = 1000L

/**
 * Schedules Graph push polls with an alarm, so a poll happens even while the device is dozing.
 *
 * Each scheduler owns a distinct alarm, identified by [schedulerId]. This matters because alarms are addressed by
 * their `PendingIntent`, and two schedulers sharing one would silently cancel each other; the app can run a pusher
 * per account.
 */
class GraphPushAlarmScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val schedulerId: String,
    private val logger: Logger,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GraphPushScheduler {
    private val coroutineScope = CoroutineScope(backgroundDispatcher)
    private val action = Uri.parse("$ALARM_SCHEME://$schedulerId")
    private val pendingAction = AtomicReference<(() -> Unit)?>(null)

    private val pendingIntent = run {
        val intent = Intent(ALARM_ACTION, action).apply {
            setPackage(context.packageName)
        }

        PendingIntentCompat.getBroadcast(context, schedulerId.hashCode(), intent, 0, false)!!
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data != action) return

            val action = pendingAction.getAndSet(null) ?: return
            coroutineScope.launch { action.invoke() }
        }
    }

    init {
        val intentFilter = IntentFilter(ALARM_ACTION).apply {
            addDataScheme(ALARM_SCHEME)
        }

        ContextCompat.registerReceiver(context, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun schedule(delaySeconds: Long, action: () -> Unit) {
        pendingAction.set(action)

        val triggerTime = SystemClock.elapsedRealtime() + delaySeconds * MILLIS_PER_SECOND

        // Exact and allowed while idle: a poll deferred until the device wakes is no better than periodic sync.
        AlarmManagerCompat.setExactAndAllowWhileIdle(
            alarmManager,
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerTime,
            pendingIntent,
        )
    }

    override fun cancel() {
        pendingAction.set(null)
        alarmManager.cancel(pendingIntent)
    }

    /**
     * Releases the broadcast receiver. Call when the pusher is discarded for good.
     */
    fun destroy() {
        cancel()

        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { logger.debug(throwable = it) { "Graph push receiver was already unregistered" } }
    }
}
