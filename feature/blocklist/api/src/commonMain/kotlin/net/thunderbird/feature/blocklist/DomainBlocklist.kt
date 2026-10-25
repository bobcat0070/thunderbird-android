package net.thunderbird.feature.blocklist

/**
 * Which kind of list a domain was found on.
 */
public enum class BlocklistKind {
    /**
     * HaGeZi's threat intelligence feeds: domains known for malware, phishing and scams.
     */
    THREAT,

    /**
     * A general blocklist tier: mostly ads, trackers and telemetry, with some malware and phishing.
     */
    GENERAL,
}

/**
 * @property listedDomain the domain the list names, which is the looked-up host itself or one of its parents - a
 *   list entry covers every subdomain under it.
 * @property kind which list it is on; a domain on both is reported as [BlocklistKind.THREAT].
 * @property listName the list's own name, e.g. `Normal`, for saying which list it is on.
 */
public data class BlocklistMatch(
    val listedDomain: String,
    val kind: BlocklistKind,
    val listName: String,
)

/**
 * Looks domains up in the DNS blocklists the user chose, entirely on the device.
 *
 * Answers from what is already loaded and never waits: before the lists are downloaded, or while they load, nothing
 * matches. Cheap enough to call while binding a list row.
 */
public fun interface DomainBlocklist {
    /**
     * @param host a host name or an email address's domain, in any case.
     * @return the list [host] is on, or `null` when it is on none of the chosen lists.
     */
    public fun match(host: String): BlocklistMatch?
}
