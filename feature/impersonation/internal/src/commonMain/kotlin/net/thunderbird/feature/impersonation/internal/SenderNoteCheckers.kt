package net.thunderbird.feature.impersonation.internal

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.thunderbird.feature.impersonation.FirstTimeSenderChecker
import net.thunderbird.feature.impersonation.KnownSendersSource
import net.thunderbird.feature.impersonation.ReplyToChecker
import net.thunderbird.feature.impersonation.ReplyToMismatch
import net.thunderbird.feature.impersonation.StoredMailCounter

/**
 * How long one reading of the reader's correspondents is used, as for the impersonation check.
 */
private val CORRESPONDENTS_REFRESH_INTERVAL = 30.minutes

/**
 * The addresses the reader has written to, has as contacts, or sends from, read now and then rather than per message.
 *
 * Calls may block on that reading, so they belong off the main thread.
 */
internal class Correspondents(
    private val knownSendersSource: KnownSendersSource,
    private val clock: Clock,
) {
    /**
     * Swapped whole rather than locked; two threads that find it stale at once each read, which costs a reading,
     * never a wrong answer.
     */
    @Volatile
    private var snapshot: Snapshot? = null

    operator fun contains(address: String): Boolean = address.trim().lowercase() in currentAddresses()

    private fun currentAddresses(): Set<String> {
        val now = clock.now()
        snapshot?.takeIf { now >= it.takenAt && now - it.takenAt < CORRESPONDENTS_REFRESH_INTERVAL }
            ?.let { return it.addresses }

        val addresses = knownSendersSource.knownSenders().correspondentAddresses
            .mapTo(HashSet()) { it.trim().lowercase() }
        snapshot = Snapshot(addresses, now)
        return addresses
    }

    private class Snapshot(val addresses: Set<String>, val takenAt: Instant)
}

/**
 * Points out a Reply-To at another domain than the sender's, unless it is someone the reader already writes to.
 *
 * The sender being known does not clear the message: a forged mail from a supplier the reader pays, answered to a
 * stranger, is the very case this is for.
 */
internal class CorrespondentsReplyToChecker(
    private val correspondents: Correspondents,
) : ReplyToChecker {
    override fun check(senderAddress: String?, replyToAddresses: List<String>): ReplyToMismatch? {
        return findReplyToMismatch(senderAddress, replyToAddresses) { it in correspondents }
    }
}

/**
 * @param isCorrespondent whether an address is the reader's own or one they write to or have as a contact.
 */
@Suppress("ReturnCount")
internal fun findReplyToMismatch(
    senderAddress: String?,
    replyToAddresses: List<String>,
    isCorrespondent: (String) -> Boolean,
): ReplyToMismatch? {
    val sender = senderAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val senderDomain = domainOf(sender)?.let(::registrableDomain) ?: return null

    val mismatch = replyToAddresses.asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && it != sender }
        .firstOrNull { replyTo ->
            val replyToDomain = domainOf(replyTo)?.let(::registrableDomain)
            replyToDomain != null && replyToDomain != senderDomain && !isCorrespondent(replyTo)
        }
        ?: return null

    return ReplyToMismatch(replyToAddress = mismatch, senderAddress = sender, senderDomain = senderDomain)
}

private fun domainOf(address: String): String? {
    return address.substringAfterLast('@', missingDelimiterValue = "").trimEnd('.').takeIf { '.' in it }
}

/**
 * A sender is new when the reader has never written to them and nothing else from them is stored.
 *
 * "Stored" is what this device holds: an account that keeps only recent mail may have forgotten an older message, so
 * the note this backs says what the device knows rather than what has ever happened.
 */
internal class StoredMailFirstTimeSenderChecker(
    private val correspondents: Correspondents,
    private val storedMailCounter: StoredMailCounter,
) : FirstTimeSenderChecker {
    override fun isFirstMail(senderAddress: String): Boolean {
        val address = senderAddress.trim().lowercase()
        if (address.isEmpty() || address in correspondents) return false

        // The message being read is stored too, so a second one is what makes the sender a repeat.
        return storedMailCounter.countMailFrom(address, atMost = 2) <= 1
    }
}
