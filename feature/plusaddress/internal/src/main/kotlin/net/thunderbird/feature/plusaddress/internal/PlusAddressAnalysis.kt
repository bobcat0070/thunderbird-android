package net.thunderbird.feature.plusaddress.internal

import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import net.thunderbird.feature.plusaddress.PlusAddressMailbox
import net.thunderbird.feature.plusaddress.PlusAddressedMail

/**
 * How the site a plus address was given to was worked out.
 */
internal enum class OwnerSource {
    /** The tag names the sender's domain, as `name+shop@` does `shop.example`. */
    TAG,

    /** The first sender whose mail to the address passed DMARC, outside spam and trash. */
    FIRST_VERIFIED_SENDER,

    /** The reader said which senders are fine. */
    READER,

    /** Nothing to go on yet. */
    UNKNOWN,
}

/**
 * What stored mail says about one plus address, before the reader's decisions are applied.
 *
 * @property owners the registrable domains the address was given to, as far as the mail shows.
 * @property senders every sender domain that wrote to it, the owners included.
 */
internal data class PlusAddressObservation(
    val address: String,
    val tag: String,
    val owners: Set<String>,
    val ownerSource: OwnerSource,
    val senders: Map<String, SenderActivity>,
)

/**
 * @property lastSeen when the latest of its mail was received, in milliseconds since the epoch.
 */
internal data class SenderActivity(val messageCount: Int, val lastSeen: Long)

/**
 * One plus address as the reader sees it: who it was given to, and who else wrote to it.
 *
 * @property givenTo the owners, the senders the reader said are fine included.
 * @property otherSenders senders that are not among [givenTo], most mail first.
 */
internal data class PlusAddressRecord(
    val key: String,
    val address: String,
    val givenTo: Set<String>,
    val ownerSource: OwnerSource,
    val isIgnored: Boolean,
    val messageCount: Int,
    val lastSeen: Long,
    val otherSenders: List<OtherSender>,
) {
    /**
     * Whether senders it was not given to have it - which says nothing until it is known who it was given to.
     */
    val isLeaked: Boolean get() = !isIgnored && givenTo.isNotEmpty() && otherSenders.isNotEmpty()
}

internal data class OtherSender(val domain: String, val messageCount: Int, val lastSeen: Long)

/**
 * Works out, from stored mail, who each of the reader's plus addresses was given to and who else has it.
 *
 * The owner is the sender the tag names, when it names one that wrote; otherwise the first sender whose mail passed
 * DMARC. Mail in spam or trash never decides it: the first mail on a leaked address is often the spam. Mail the
 * reader sent themselves is left out.
 */
internal object PlusAddressAnalysis {

    fun observe(
        mailbox: PlusAddressMailbox,
        domains: RegistrableDomains = PublicSuffixRegistrableDomains,
    ): Map<String, PlusAddressObservation> {
        val readers = ReaderAddresses(mailbox.readerAddresses)

        return mailbox.mail
            .filterNot { mail -> mail.senderAddress?.let(readers::contains) == true }
            .flatMap { mail -> deliveriesOf(mail, readers, domains) }
            .groupBy { it.plusAddress.key }
            .mapValues { (_, deliveries) -> observation(deliveries) }
    }

    /**
     * One delivery for each of the reader's plus addresses [mail] was sent to; none for mail without a sender.
     */
    private fun deliveriesOf(
        mail: PlusAddressedMail,
        readers: ReaderAddresses,
        domains: RegistrableDomains,
    ): List<Delivery> {
        val senderDomain = domains.senderDomainOf(mail.senderAddress) ?: return emptyList()

        return mail.recipientAddresses
            .mapNotNull(readers::plusAddressOf)
            .distinctBy { it.key }
            .map { plusAddress -> Delivery(plusAddress, senderDomain, mail) }
    }

    private fun observation(deliveries: List<Delivery>): PlusAddressObservation {
        val plusAddress = deliveries.first().plusAddress
        val trusted = deliveries.filterNot { it.mail.isInSpamOrTrash }

        val namedByTag = trusted.map { it.senderDomain }
            .filterTo(mutableSetOf()) { domain -> tagNamesDomain(plusAddress.tag, domain) }
        val firstVerified = trusted.filter { it.mail.isSenderAuthenticated }
            .minByOrNull { it.mail.receivedAt }
            ?.senderDomain

        val (owners, source) = when {
            namedByTag.isNotEmpty() -> namedByTag to OwnerSource.TAG
            firstVerified != null -> setOf(firstVerified) to OwnerSource.FIRST_VERIFIED_SENDER
            else -> emptySet<String>() to OwnerSource.UNKNOWN
        }

        val senders = deliveries.groupBy { it.senderDomain }.mapValues { (_, mail) ->
            SenderActivity(messageCount = mail.size, lastSeen = mail.maxOf { it.mail.receivedAt })
        }

        return PlusAddressObservation(
            address = plusAddress.address,
            tag = plusAddress.tag,
            owners = owners,
            ownerSource = source,
            senders = senders,
        )
    }

    fun record(
        key: String,
        observation: PlusAddressObservation,
        decisions: PlusAddressDecisionSnapshot,
    ): PlusAddressRecord {
        val allowed = decisions.allowedSenders[key].orEmpty()
        val givenTo = observation.owners + allowed
        val source = if (observation.owners.isEmpty() && allowed.isNotEmpty()) {
            OwnerSource.READER
        } else {
            observation.ownerSource
        }
        val activity = observation.senders.values

        return PlusAddressRecord(
            key = key,
            address = observation.address,
            givenTo = givenTo,
            ownerSource = source,
            isIgnored = key in decisions.ignored,
            messageCount = activity.sumOf { it.messageCount },
            lastSeen = activity.maxOfOrNull { it.lastSeen } ?: 0L,
            otherSenders = observation.senders
                .filterKeys { it !in givenTo }
                .map { (domain, sender) -> OtherSender(domain, sender.messageCount, sender.lastSeen) }
                .sortedWith(compareByDescending<OtherSender> { it.messageCount }.thenBy { it.domain }),
        )
    }

    private class Delivery(val plusAddress: PlusAddress, val senderDomain: String, val mail: PlusAddressedMail)
}
