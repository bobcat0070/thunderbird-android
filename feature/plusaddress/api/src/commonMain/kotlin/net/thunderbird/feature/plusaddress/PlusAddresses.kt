package net.thunderbird.feature.plusaddress

/**
 * A plus address of the reader's that mail arrived on from a sender it was not given to.
 *
 * Plus addresses (`name+shop@example.com`) are handed out one per site, so mail on one from anybody else means the
 * address got from that site to them: sold, shared with a partner, or stolen in a breach.
 *
 * @property plusAddress the reader's plus address the mail was sent to, in lower case and in the form
 *   [PlusAddressDecisions] takes: for a mailbox that ignores dots in the user name, without them.
 * @property givenTo the registrable domains the address is taken to belong to: the site it was given to, and any the
 *   reader said are fine.
 * @property senderDomain the registrable domain of the sender that is not one of them.
 */
data class PlusAddressLeak(
    val plusAddress: String,
    val givenTo: Set<String>,
    val senderDomain: String,
)

/**
 * Checks mail for a plus address used by a sender it was not given to.
 */
fun interface PlusAddressLeakChecker {
    /**
     * @param recipientAddresses the addresses the mail was delivered or written to.
     * @param senderAddress the From address.
     * @return the leak, or `null` when no plus address of the reader's is involved, the address's owner is not known
     *   yet, or the sender is its owner.
     */
    fun check(recipientAddresses: Collection<String>, senderAddress: String?): PlusAddressLeak?
}

/**
 * Says which of the reader's plus addresses a sender has, as when a breach notice says the sender lost its data.
 *
 * Calls may read stored mail, so they belong off the main thread.
 */
fun interface PlusAddressLookup {
    /**
     * @return the reader's plus addresses that were given to [senderAddress]'s registrable domain, or that the reader
     *   said it may use; empty when there are none.
     */
    fun addressesGivenTo(senderAddress: String): List<String>
}

/**
 * A stored message sent to one of the reader's plus addresses, with what working out the address's owner needs.
 *
 * @property recipientAddresses the To and Cc addresses.
 * @property receivedAt when the mailbox received it, in milliseconds since the epoch.
 * @property isSenderAuthenticated whether the receiving server reported that it passed DMARC.
 * @property isInSpamOrTrash whether it is in a spam or trash folder, which never decides an owner.
 */
data class PlusAddressedMail(
    val recipientAddresses: List<String>,
    val senderAddress: String?,
    val receivedAt: Long,
    val isSenderAuthenticated: Boolean,
    val isInSpamOrTrash: Boolean,
)

/**
 * The reader's own addresses and the stored mail sent to plus variants of them.
 */
data class PlusAddressMailbox(
    val readerAddresses: Set<String>,
    val mail: List<PlusAddressedMail>,
)

/**
 * Reads [PlusAddressMailbox] from stored mail. It scans every account, so it is asked rarely and off the main thread.
 */
fun interface PlusAddressMailSource {
    fun read(): PlusAddressMailbox
}

/**
 * What the reader said about their plus addresses, which overrides what the app works out.
 */
interface PlusAddressDecisions {
    /**
     * Treats [domain] as one of the senders [plusAddress] was given to, so its mail is not reported as a leak.
     */
    fun allowSender(plusAddress: String, domain: String)

    /**
     * Stops or resumes checking [plusAddress]: for a tag used with many senders, like `name+lists@`, every one of
     * them would otherwise look like a leak.
     */
    fun setIgnored(plusAddress: String, isIgnored: Boolean)
}

/**
 * The reader's decisions about their plus addresses, so that settings export can carry them to another device.
 */
interface PlusAddressSettings {
    fun exportDecisions(): PlusAddressDecisionSnapshot

    /**
     * Adds [decisions] to the decisions already here.
     */
    fun importDecisions(decisions: PlusAddressDecisionSnapshot)
}

/**
 * @property allowedSenders for each plus address, the sender domains the reader said are fine.
 * @property ignored the plus addresses the reader stopped checking.
 */
data class PlusAddressDecisionSnapshot(
    val allowedSenders: Map<String, Set<String>>,
    val ignored: Set<String>,
)
