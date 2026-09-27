package com.fsck.k9.controller

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MILLIS_PER_SECOND = 1000L

/**
 * Keeps a message that was just sent in the Outbox for a few seconds, so it can still be taken back.
 *
 * A message is held from the moment it is queued until either the delay runs out, when it is released to be sent,
 * or the user undoes it. Whichever comes first wins - a message the user took back is never sent, and one already
 * released can no longer be taken back - so the two cannot race.
 *
 * Kept in memory: if the app is stopped while a message is held, the next send run simply sends it, which is what
 * the user was going to get anyway once they stopped reaching for "Undo".
 *
 * @param delaySeconds how long to hold a message, read when it is sent; 0 turns holding off.
 * @param schedule runs an action once a delay has passed.
 */
class UndoSendHold @JvmOverloads constructor(
    private val delaySeconds: () -> Int,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val schedule: (delayMillis: Long, action: Runnable) -> Unit = defaultSchedule(),
) {
    /**
     * A message being held, and when it will be let go.
     */
    data class HeldMessage(val accountUuid: String, val messageId: Long, val releaseAtMillis: Long)

    private val lock = Any()
    private val held = MutableStateFlow<List<HeldMessage>>(emptyList())

    /**
     * The messages currently held, most recently sent last, for offering "Undo".
     */
    val heldMessages: StateFlow<List<HeldMessage>> = held.asStateFlow()

    /**
     * Holds a message that was just queued, if undo send is on.
     *
     * @param onRelease run once the delay has passed and the message was not taken back - which is when it
     *   should be sent.
     * @return whether it is being held; `false` means undo send is off and the message should go now.
     */
    fun hold(accountUuid: String, messageId: Long, onRelease: Runnable): Boolean {
        val delayMillis = delaySeconds().coerceAtLeast(0) * MILLIS_PER_SECOND
        if (delayMillis == 0L) return false

        synchronized(lock) {
            held.value = held.value + HeldMessage(accountUuid, messageId, currentTimeMillis() + delayMillis)
        }
        schedule(delayMillis) {
            if (remove(accountUuid, messageId)) onRelease.run()
        }

        return true
    }

    fun isHeld(accountUuid: String, messageId: Long): Boolean {
        return held.value.any { it.accountUuid == accountUuid && it.messageId == messageId }
    }

    /**
     * Takes a held message back.
     *
     * @return whether it was still held, and so will not be sent; `false` when it has already been released.
     */
    fun cancel(accountUuid: String, messageId: Long): Boolean = remove(accountUuid, messageId)

    private fun remove(accountUuid: String, messageId: Long): Boolean {
        synchronized(lock) {
            val current = held.value
            val remaining = current.filterNot { it.accountUuid == accountUuid && it.messageId == messageId }
            held.value = remaining

            return remaining.size != current.size
        }
    }
}

private fun defaultSchedule(): (Long, Runnable) -> Unit {
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "UndoSendHold").apply { isDaemon = true }
    }

    return { delayMillis, action -> executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS) }
}
