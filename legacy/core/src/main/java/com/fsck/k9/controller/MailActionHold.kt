package com.fsck.k9.controller

import app.k9mail.legacy.message.controller.MessageReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long a delete or move waits for "Undo".
 */
const val MAIL_ACTION_HOLD_MILLIS = 3_000L

/**
 * Waits a moment before deleting or moving mail, so the reader can take it back.
 *
 * Nothing happens to the mail until the wait is over: the messages only leave the lists, and "Undo" puts them back
 * as they were. Doing it the other way round - moving the mail and moving it back - would mean finding it again in
 * the folder it went to, under the new id the server gave it there. Whichever comes first wins - the wait running
 * out, which carries the action out, or "Undo", which drops it - so the two cannot race.
 *
 * Kept in memory: an action still waiting when the app is stopped would never happen, so the screen that offers
 * "Undo" carries out whatever is waiting with [releaseAll] when it goes away.
 *
 * @param schedule runs an action once a delay has passed.
 */
class MailActionHold @JvmOverloads constructor(
    private val delayMillis: Long = MAIL_ACTION_HOLD_MILLIS,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val schedule: (delayMillis: Long, action: Runnable) -> Unit = defaultSchedule(),
) {
    /**
     * What is being done to the mail, for saying so beside "Undo".
     */
    sealed interface Kind {
        data object Delete : Kind
        data object Archive : Kind
        data object Spam : Kind

        /**
         * @param folderId the folder the mail is going to, in the account of the messages.
         */
        data class Move(val folderId: Long) : Kind
    }

    /**
     * An action waiting to be carried out.
     *
     * @param messages the messages it will remove from the lists they are in.
     */
    data class HeldAction(
        val id: Long,
        val kind: Kind,
        val messages: List<MessageReference>,
        val releaseAtMillis: Long,
    )

    private class Entry(val action: HeldAction, val perform: Runnable)

    private val lock = Any()
    private val nextId = AtomicLong()
    private val entries = mutableListOf<Entry>()
    private val held = MutableStateFlow<List<HeldAction>>(emptyList())

    /**
     * The actions waiting, the most recent last.
     */
    val heldActions: StateFlow<List<HeldAction>> = held.asStateFlow()

    /**
     * Holds an action on [messages] for a moment before running [perform].
     *
     * @param perform carries the action out; run on a background thread once the wait is over, unless undone.
     */
    fun hold(kind: Kind, messages: List<MessageReference>, perform: Runnable): HeldAction {
        val action = HeldAction(nextId.incrementAndGet(), kind, messages, currentTimeMillis() + delayMillis)
        synchronized(lock) {
            entries += Entry(action, perform)
            publish()
        }

        schedule(delayMillis) { take(action.id)?.perform?.run() }

        return action
    }

    /**
     * Whether [message] is waiting to be deleted or moved, and so should be left out of the lists.
     */
    fun isHeld(message: MessageReference): Boolean = held.value.any { message in it.messages }

    /**
     * Drops a waiting action, which puts its messages back where they were.
     *
     * @return whether it was still waiting; `false` when it has already been carried out.
     */
    fun undo(actionId: Long): Boolean = take(actionId) != null

    /**
     * Carries out every waiting action now, in the background, for when "Undo" can no longer be reached.
     */
    fun releaseAll() {
        val released = synchronized(lock) {
            entries.toList().also {
                entries.clear()
                publish()
            }
        }

        // On the thread the waits run out on, not the caller's: this is called from a screen going away.
        for (entry in released) {
            schedule(0L, entry.perform)
        }
    }

    private fun take(actionId: Long): Entry? {
        synchronized(lock) {
            val entry = entries.firstOrNull { it.action.id == actionId } ?: return null
            entries.remove(entry)
            publish()

            return entry
        }
    }

    private fun publish() {
        held.value = entries.map { it.action }
    }
}

private fun defaultSchedule(): (Long, Runnable) -> Unit {
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MailActionHold").apply { isDaemon = true }
    }

    return { delayMillis, action -> executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS) }
}
