package net.thunderbird.feature.blocklist.internal

import net.thunderbird.core.preference.blocklist.DomainBlocklistTier

/**
 * Where HaGeZi's lists are fetched from: jsDelivr's mirror of the project's GitHub repository, which the project
 * itself lists as a download location. It serves the files compressed, so the Normal list is about a megabyte.
 */
private const val LIST_BASE_URL = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/"

/**
 * The HaGeZi lists the app can use, in their wildcard-domains form: one domain per line, covering its subdomains.
 *
 * @property fileStem the list's name in the repository.
 * @property displayName the list's own name, as HaGeZi writes it.
 */
internal enum class HagezeiList(val fileStem: String, val displayName: String) {
    LIGHT("light", "Light"),
    NORMAL("multi", "Normal"),
    PRO("pro", "Pro"),
    PRO_PLUS("pro.plus", "Pro++"),
    ULTIMATE("ultimate", "Ultimate"),

    /**
     * The medium threat intelligence feeds: about 560,000 malware, phishing and scam domains. The full feed is four
     * times that and too much to keep on a phone; the mini one leaves out most of what makes it worth having.
     */
    THREATS("tif.medium", "Threat Intelligence Feeds"),
    ;

    val url: String get() = "$LIST_BASE_URL$fileStem-onlydomains.txt"

    companion object {
        fun forTier(tier: DomainBlocklistTier): HagezeiList? = when (tier) {
            DomainBlocklistTier.OFF -> null
            DomainBlocklistTier.LIGHT -> LIGHT
            DomainBlocklistTier.NORMAL -> NORMAL
            DomainBlocklistTier.PRO -> PRO
            DomainBlocklistTier.PRO_PLUS -> PRO_PLUS
            DomainBlocklistTier.ULTIMATE -> ULTIMATE
        }
    }
}
