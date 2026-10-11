package net.thunderbird.feature.impersonation.internal

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.thunderbird.feature.impersonation.Impersonation
import net.thunderbird.feature.impersonation.ImpersonationChecker
import net.thunderbird.feature.impersonation.KnownSendersSource
import org.koin.dsl.module

/**
 * How long one reading of who the reader knows is used before it is read again. Long enough that opening message
 * after message does not rescan stored mail; short enough that someone just written to counts within the hour.
 */
private val REFRESH_INTERVAL = 30.minutes

/**
 * The most senders whose answer one reading remembers. A unified inbox of a few thousand messages has far fewer
 * senders; the limit only keeps an unusual mailbox from growing the memory without bound.
 */
private const val MAX_REMEMBERED_SENDERS = 10_000

/**
 * Checks senders against a reading of [KnownSendersSource] that is refreshed now and then rather than per message.
 *
 * Each sender's answer is remembered for as long as the reading is used: a message list asks about every message
 * each time it loads, and the same senders over and over, and checking them took most of the time a large list
 * took to load.
 *
 * Calls may block on that reading, so they belong off the main thread.
 */
internal class CachingImpersonationChecker(
    private val knownSendersSource: KnownSendersSource,
    private val clock: Clock,
) : ImpersonationChecker {
    /**
     * Swapped whole rather than locked: two threads that find it stale at once each build an index, and whichever
     * lands last is kept. That costs one extra reading, never a wrong answer.
     */
    @Volatile
    private var snapshot: Snapshot? = null

    override fun check(senderName: String?, senderAddress: String?): Impersonation? {
        val snapshot = currentSnapshot()
        val sender = Sender(senderName, senderAddress)
        synchronized(snapshot.answers) {
            if (sender in snapshot.answers) return snapshot.answers[sender]
        }

        val answer = findImpersonation(senderName, senderAddress, snapshot.index)
        synchronized(snapshot.answers) {
            if (snapshot.answers.size < MAX_REMEMBERED_SENDERS) snapshot.answers[sender] = answer
        }

        return answer
    }

    private fun currentSnapshot(): Snapshot {
        val now = clock.now()
        snapshot?.takeIf { now >= it.takenAt && now - it.takenAt < REFRESH_INTERVAL }?.let { return it }

        return Snapshot(KnownSenderIndex(knownSendersSource.knownSenders()), now).also { snapshot = it }
    }

    private class Snapshot(val index: KnownSenderIndex, val takenAt: Instant) {
        /**
         * The answer for each sender checked against [index], `null` for one that impersonates nobody.
         */
        val answers = HashMap<Sender, Impersonation?>()
    }

    private data class Sender(val name: String?, val address: String?)
}

/**
 * Binds the impersonation check. [KnownSendersSource] is bound by the app.
 */
val featureImpersonationModule = module {
    single<ImpersonationChecker> {
        CachingImpersonationChecker(knownSendersSource = get(), clock = get())
    }
}
