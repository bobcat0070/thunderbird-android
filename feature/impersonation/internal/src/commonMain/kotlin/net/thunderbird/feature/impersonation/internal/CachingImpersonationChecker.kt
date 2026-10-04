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
 * Checks senders against a reading of [KnownSendersSource] that is refreshed now and then rather than per message.
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
        return findImpersonation(senderName, senderAddress, currentIndex())
    }

    private fun currentIndex(): KnownSenderIndex {
        val now = clock.now()
        snapshot?.takeIf { now >= it.takenAt && now - it.takenAt < REFRESH_INTERVAL }?.let { return it.index }

        return KnownSenderIndex(knownSendersSource.knownSenders()).also { snapshot = Snapshot(it, now) }
    }

    private class Snapshot(val index: KnownSenderIndex, val takenAt: Instant)
}

/**
 * Binds the impersonation check. [KnownSendersSource] is bound by the app.
 */
val featureImpersonationModule = module {
    single<ImpersonationChecker> {
        CachingImpersonationChecker(knownSendersSource = get(), clock = get())
    }
}
