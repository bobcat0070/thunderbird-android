package net.thunderbird.feature.impersonation.internal

import net.thunderbird.feature.impersonation.Impersonation
import net.thunderbird.feature.impersonation.KnownSenders

/**
 * The shortest name label a lookalike is looked for against. Short names collide by accident - `ups` and `usps` are
 * both real - and are rarely what gets imitated.
 */
private const val MINIMUM_IMITATED_LABEL_LENGTH = 5

/**
 * The fewest letters, and words, a display name needs before matching it means anything. "Support" or "Info" is
 * the name of half the senders in the world.
 */
private const val MINIMUM_NAME_LETTERS = 6
private const val MINIMUM_NAME_WORDS = 2

/**
 * Domains where anyone can have an address. A known name at one of these is only as trustworthy as the exact
 * address; a known name on a company's own verified domain is how services like GitHub and Google Docs label mail
 * they send on a person's behalf.
 */
private val FREE_MAIL_DOMAINS = setOf(
    "aol.com", "comcast.net", "fastmail.com", "gmail.com", "gmx.com", "gmx.net", "googlemail.com", "hey.com",
    "hotmail.com", "icloud.com", "live.com", "mac.com", "mail.com", "me.com", "msn.com", "outlook.com",
    "proton.me", "protonmail.com", "yahoo.com", "yandex.com", "zoho.com",
)

/**
 * Words that make a display name a role rather than a person. "Support Team" written to once is no reason to doubt
 * every other support team's mail.
 */
private val ROLE_WORDS = setOf(
    "account", "accounts", "admin", "billing", "customer", "help", "helpdesk", "info", "noreply", "notification",
    "notifications", "office", "sales", "security", "service", "services", "support", "team",
)

private val ADDRESS_IN_TEXT = Regex("""[\p{L}\p{N}._%+-]+@([\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+)""")
private val DOMAIN_IN_TEXT = Regex("""(?<![@\p{L}\p{N}.-])((?:[\p{L}\p{N}-]+\.)+\p{L}{2,})(?![\p{L}\p{N}-])""")
private val NON_NAME_CHARACTERS = Regex("""[^\p{L}\p{N}\s]""")
private val WHITESPACE = Regex("""\s+""")

/**
 * [KnownSenders] arranged for checking one sender after another without scanning it each time.
 */
internal class KnownSenderIndex(knownSenders: KnownSenders) {
    val addressesByNameKey: Map<String, Set<String>> = knownSenders.addressesByName.entries
        .mapNotNull { (name, addresses) -> nameKey(name)?.let { it to addresses.map(String::lowercase) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, addressLists) -> addressLists.flatten().toSet() }

    val verifiedDomains: Set<String> = knownSenders.verifiedSenderDomains.map(::registrableDomain).toSet()

    val knownDomains: Set<String> = verifiedDomains +
        knownSenders.correspondentAddresses.mapNotNull { domainOf(it)?.let(::registrableDomain) } +
        addressesByNameKey.values.flatten().mapNotNull { domainOf(it)?.let(::registrableDomain) }

    /**
     * Known domains by what they look like, for finding a sender's domain that looks the same but is not.
     */
    val knownDomainsBySkeleton: Map<String, String> = knownDomains
        .filter { nameLabel(it).length >= MINIMUM_IMITATED_LABEL_LENGTH }
        .associateBy { lookalikeKey(it) }
}

/**
 * Finds what a sender is impersonating, if anything.
 *
 * Each rule looks for a claim the sender makes - a name, an address in the name, a domain shaped like another - and
 * checks it against what the address actually is. The rules were tuned against a real mailbox of about 1,200
 * messages, where they flagged five: two strangers using the reader's own name, two survey invitations dressed as a
 * law firm, and one unfamiliar domain under the reader's name. Brands whose display name is their website, and
 * services that put a person's name on their own notifications, are deliberately left alone.
 */
internal fun findImpersonation(senderName: String?, senderAddress: String?, index: KnownSenderIndex): Impersonation? {
    val address = senderAddress?.trim()?.lowercase()?.takeIf { '@' in it } ?: return null
    val asciiDomain = domainOf(address) ?: return null
    val senderDomain = registrableDomain(asciiDomain)
    val name = senderName?.trim().orEmpty()

    return addressInName(name, senderDomain)
        ?: domainInName(name, senderDomain, index)
        ?: lookalikeDomain(asciiDomain, senderDomain, index)
        ?: mixedScriptDomain(asciiDomain)
        ?: knownName(name, address, senderDomain, index)
}

private fun addressInName(name: String, senderDomain: String): Impersonation? {
    return ADDRESS_IN_TEXT.findAll(name)
        .firstOrNull { match -> registrableDomain(match.groupValues[1]) != senderDomain }
        ?.let { match -> Impersonation.AddressInName(match.value.lowercase(), senderDomain) }
}

/**
 * Only a domain that has proven itself to the reader counts, and only when the sender's own name label does not
 * contain it: "ConsumerLab.com" mailing from `consumerlabmail.com` is a brand and its mail service, not a lie.
 */
private fun domainInName(name: String, senderDomain: String, index: KnownSenderIndex): Impersonation? {
    return DOMAIN_IN_TEXT.findAll(name)
        .map { registrableDomain(it.groupValues[1]) }
        .firstOrNull { namedDomain ->
            namedDomain != senderDomain &&
                namedDomain in index.verifiedDomains &&
                nameLabel(namedDomain) !in nameLabel(senderDomain)
        }
        ?.let { namedDomain -> Impersonation.DomainInName(namedDomain, senderDomain) }
}

private fun lookalikeDomain(asciiDomain: String, senderDomain: String, index: KnownSenderIndex): Impersonation? {
    if (senderDomain in index.knownDomains) return null

    val readable = registrableDomain(unicodeDomain(asciiDomain))
    val knownDomain = index.knownDomainsBySkeleton[lookalikeKey(readable)] ?: return null

    return Impersonation.LookalikeDomain(senderDomain = readable, knownDomain = knownDomain)
}

private fun mixedScriptDomain(asciiDomain: String): Impersonation? {
    val readable = unicodeDomain(asciiDomain)

    return if (hasMixedScriptLabel(readable)) Impersonation.MixedScriptDomain(readable) else null
}

/**
 * A known name on an address that is not one of theirs. Not flagged when the address is on a verified domain that
 * is not free mail, or on the same organisation's domain as one of that person's known addresses - both are how
 * real people's names reach the inbox from somewhere other than their own mailbox.
 */
private fun knownName(name: String, address: String, senderDomain: String, index: KnownSenderIndex): Impersonation? {
    val key = nameKey(name) ?: return null
    val knownAddresses = index.addressesByNameKey[key] ?: return null
    if (address in knownAddresses) return null

    val isFreeMail = senderDomain in FREE_MAIL_DOMAINS
    if (!isFreeMail && senderDomain in index.verifiedDomains) return null

    val knownOrganisations = knownAddresses.mapNotNull { domainOf(it)?.let(::registrableDomain) }
        .filterNot { it in FREE_MAIL_DOMAINS }
    if (!isFreeMail && senderDomain in knownOrganisations) return null

    return Impersonation.KnownName(name = name, senderAddress = address)
}

private fun domainOf(address: String): String? =
    address.substringAfterLast('@', missingDelimiterValue = "").trim().lowercase().takeIf { it.contains('.') }

private fun lookalikeKey(registrableDomain: String): String =
    "${skeleton(nameLabel(registrableDomain))}|${suffixOf(registrableDomain)}"

/**
 * A display name reduced to what a reader compares: letters and digits, folded together where they look alike,
 * one space between words. `null` for a name too short or too generic to mean anything.
 */
internal fun nameKey(name: String): String? {
    val words = NON_NAME_CHARACTERS.replace(name, " ").trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.size < MINIMUM_NAME_WORDS || words.any { it.lowercase() in ROLE_WORDS }) return null

    val key = words.joinToString(" ") { word -> word.lowercase().map { confusableLetter(it) }.joinToString("") }
    return key.takeIf { key.count(Char::isLetter) >= MINIMUM_NAME_LETTERS }
}

private fun confusableLetter(character: Char): Char = skeleton(character.toString()).firstOrNull() ?: character
