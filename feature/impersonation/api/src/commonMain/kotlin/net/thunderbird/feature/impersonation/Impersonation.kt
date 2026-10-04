package net.thunderbird.feature.impersonation

/**
 * Something a sender's name or address claims that the address it came from does not back up.
 *
 * Every case names what the reader is being led to believe and where the message really came from, so a warning
 * can say both rather than only that something is wrong.
 */
public sealed interface Impersonation {
    /**
     * The display name carries an email address at another domain, as in `"service@bank.example" <x@elsewhere>`.
     */
    public data class AddressInName(val namedAddress: String, val senderDomain: String) : Impersonation

    /**
     * The display name names a domain that has sent the reader verified mail, but the message came from another.
     */
    public data class DomainInName(val namedDomain: String, val senderDomain: String) : Impersonation

    /**
     * The sender's domain is written to look like one the reader knows: a digit for a letter, `rn` for `m`, a
     * letter from another alphabet.
     */
    public data class LookalikeDomain(val senderDomain: String, val knownDomain: String) : Impersonation

    /**
     * The sender's domain mixes letters from different alphabets in one name, which no ordinary domain does.
     */
    public data class MixedScriptDomain(val senderDomain: String) : Impersonation

    /**
     * The display name is the name of someone the reader knows, but the address is not one of theirs.
     */
    public data class KnownName(val name: String, val senderAddress: String) : Impersonation
}

/**
 * Checks a sender for impersonation.
 */
public fun interface ImpersonationChecker {
    /**
     * @return what the sender is impersonating, or `null` when nothing stands out.
     */
    public fun check(senderName: String?, senderAddress: String?): Impersonation?
}

/**
 * Who the reader already knows, as far as the device can tell. The checker compares senders against this.
 *
 * @param addressesByName the addresses known for each display name: people written to, contacts the account's
 *   provider lists, and the reader's own identities.
 * @param correspondentAddresses addresses the reader has written to or has as contacts, and their own.
 * @param verifiedSenderDomains domains of senders whose mail passed DMARC on its way to the reader. Being in here
 *   proves a domain is real and has sent the reader mail, which is what a lookalike is trying to borrow.
 */
public data class KnownSenders(
    val addressesByName: Map<String, Set<String>>,
    val correspondentAddresses: Set<String>,
    val verifiedSenderDomains: Set<String>,
)

/**
 * Provides [KnownSenders]. Reading it may scan stored mail, so it is asked rarely and off the main thread.
 */
public fun interface KnownSendersSource {
    public fun knownSenders(): KnownSenders
}
