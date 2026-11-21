package net.thunderbird.feature.plusaddress.internal

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.thunderbird.feature.plusaddress.PlusAddressLeak
import net.thunderbird.feature.plusaddress.PlusAddressLeakChecker
import net.thunderbird.feature.plusaddress.PlusAddressMailSource

/**
 * How long one reading of stored mail is used before it is read again. Long enough that opening message after
 * message does not rescan stored mail; short enough that a new plus address has its owner within the hour.
 */
private val REFRESH_INTERVAL = 30.minutes

/**
 * The plus addresses the reader has, as of a recent reading of stored mail.
 */
internal interface PlusAddressOverview {
    /**
     * @param refresh read stored mail again rather than use a recent reading.
     */
    fun records(refresh: Boolean): List<PlusAddressRecord>
}

/**
 * Checks mail against a reading of [PlusAddressMailSource] that is refreshed now and then rather than per message:
 * a message list asks about every row each time it loads.
 *
 * The reader's decisions are applied on every check, so "This sender is fine" counts at once. Calls may block on a
 * reading, so they belong off the main thread.
 */
internal class CachingPlusAddressLeakChecker(
    private val mailSource: PlusAddressMailSource,
    private val decisionStore: PlusAddressDecisionStore,
    private val clock: Clock,
    private val domains: RegistrableDomains = PublicSuffixRegistrableDomains,
) : PlusAddressLeakChecker, PlusAddressOverview {
    /**
     * Swapped whole rather than locked: two threads that find it stale at once each read stored mail, and whichever
     * lands last is kept. That costs one extra reading, never a wrong answer.
     */
    @Volatile
    private var snapshot: Snapshot? = null

    override fun check(recipientAddresses: Collection<String>, senderAddress: String?): PlusAddressLeak? {
        // Most mail has no plus address at all, and must not cost a reading of stored mail.
        if (senderAddress == null || recipientAddresses.none { '+' in it }) return null

        return leakIn(currentSnapshot(refresh = false), recipientAddresses, senderAddress)
    }

    private fun leakIn(
        snapshot: Snapshot,
        recipientAddresses: Collection<String>,
        senderAddress: String,
    ): PlusAddressLeak? {
        val senderDomain = domains.senderDomainOf(senderAddress)
        if (senderDomain == null || snapshot.readers.contains(senderAddress)) return null

        val decisions = decisionStore.decisions()
        return recipientAddresses.asSequence()
            .mapNotNull(snapshot.readers::plusAddressOf)
            .filter { it.key !in decisions.ignored }
            .mapNotNull { plusAddress ->
                snapshot.observations[plusAddress.key]?.let { observation ->
                    val givenTo = observation.owners + decisions.allowedSenders[plusAddress.key].orEmpty()
                    PlusAddressLeak(plusAddress.key, givenTo, senderDomain)
                }
            }
            .firstOrNull { leak -> leak.givenTo.isNotEmpty() && leak.senderDomain !in leak.givenTo }
    }

    override fun records(refresh: Boolean): List<PlusAddressRecord> {
        val snapshot = currentSnapshot(refresh)
        val decisions = decisionStore.decisions()

        return snapshot.observations
            .map { (key, observation) -> PlusAddressAnalysis.record(key, observation, decisions) }
            .sortedWith(
                compareByDescending<PlusAddressRecord> { it.isLeaked }
                    .thenBy { it.isIgnored }
                    .thenByDescending { it.lastSeen },
            )
    }

    private fun currentSnapshot(refresh: Boolean): Snapshot {
        val now = clock.now()
        if (!refresh) {
            snapshot?.takeIf { now >= it.takenAt && now - it.takenAt < REFRESH_INTERVAL }?.let { return it }
        }

        val mailbox = mailSource.read()
        return Snapshot(
            readers = ReaderAddresses(mailbox.readerAddresses),
            observations = PlusAddressAnalysis.observe(mailbox, domains),
            takenAt = now,
        ).also { snapshot = it }
    }

    private class Snapshot(
        val readers: ReaderAddresses,
        val observations: Map<String, PlusAddressObservation>,
        val takenAt: Instant,
    )
}
