package net.thunderbird.feature.newsletter.internal

import android.content.Context
import androidx.core.content.edit
import kotlin.time.Duration.Companion.days
import net.thunderbird.feature.newsletter.StillMailing
import net.thunderbird.feature.newsletter.UnsubscribeFollowUp
import net.thunderbird.feature.newsletter.UnsubscribeHistory
import net.thunderbird.feature.newsletter.UnsubscribeHistorySettings

/**
 * How long a sender is given to stop after the reader unsubscribes. Gmail and Yahoo ask bulk senders to stop within
 * two days; the extra days leave room for mail already queued and for slower senders.
 */
internal val UNSUBSCRIBE_GRACE_PERIOD = 5.days

/**
 * What an imported sender address must look like; anything else is dropped.
 */
private val ADDRESS = Regex("[^\\s@]+@[a-z0-9.-]+\\.[a-z0-9-]+")

/**
 * The unsubscribes as the follow-up check reads them.
 */
internal interface UnsubscribeStore : UnsubscribeHistory {
    /**
     * @return when the reader unsubscribed from [senderAddress], or `null` if they have not.
     */
    fun unsubscribedAt(senderAddress: String): Long?
}

/**
 * Keeps the reader's unsubscribes in the app's private preferences, one entry per sender address.
 */
internal class SharedPreferencesUnsubscribeHistory(
    context: Context,
) : UnsubscribeStore, UnsubscribeHistorySettings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun recordUnsubscribe(senderAddress: String, atMillis: Long) {
        val address = senderAddress.normalized() ?: return
        preferences.edit { putLong(address, atMillis) }
    }

    override fun forget(senderAddress: String) {
        val address = senderAddress.normalized() ?: return
        preferences.edit { remove(address) }
    }

    override fun unsubscribedAt(senderAddress: String): Long? {
        val address = senderAddress.normalized() ?: return null
        return (preferences.all[address] as? Long)?.takeIf { it > 0 }
    }

    override fun exportUnsubscribes(): Map<String, Long> {
        return preferences.all.mapNotNull { (address, value) -> (value as? Long)?.let { address to it } }.toMap()
    }

    override fun importUnsubscribes(unsubscribes: Map<String, Long>) {
        val valid = unsubscribes.mapNotNull { (address, at) ->
            address.normalized()?.takeIf { at > 0 && ADDRESS.matches(it) }?.let { it to at }
        }
        if (valid.isEmpty()) return

        preferences.edit {
            for ((address, at) in valid) {
                val current = preferences.all[address] as? Long ?: 0L
                putLong(address, maxOf(current, at))
            }
        }
    }

    private fun String.normalized(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

    private companion object {
        const val PREFERENCES_NAME = "unsubscribes"
    }
}

/**
 * Points out mail that reached the mailbox more than [UNSUBSCRIBE_GRACE_PERIOD] after the reader unsubscribed from
 * its sender. Mail that was already on its way, or sent before the sender could act on the request, is let be.
 */
internal class StoreUnsubscribeFollowUp(
    private val store: UnsubscribeStore,
) : UnsubscribeFollowUp {
    override fun check(senderAddress: String, receivedAt: Long): StillMailing? {
        return store.unsubscribedAt(senderAddress)
            ?.takeIf { unsubscribedAt -> receivedAt - unsubscribedAt > UNSUBSCRIBE_GRACE_PERIOD.inWholeMilliseconds }
            ?.let { unsubscribedAt ->
                StillMailing(senderAddress = senderAddress.trim().lowercase(), unsubscribedAt = unsubscribedAt)
            }
    }
}
