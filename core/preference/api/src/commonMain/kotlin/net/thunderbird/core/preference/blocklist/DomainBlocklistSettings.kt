package net.thunderbird.core.preference.blocklist

/**
 * Which of HaGeZi's DNS blocklists a sender's domain and a link's site are looked up in.
 *
 * The tiers are one list each, from the smallest to the most thorough; each is a superset of the one before it.
 * [OFF] downloads and checks nothing.
 */
enum class DomainBlocklistTier {
    OFF,
    LIGHT,
    NORMAL,
    PRO,
    PRO_PLUS,
    ULTIMATE,
}

val DOMAIN_BLOCKLIST_SETTINGS_DEFAULT_TIER = DomainBlocklistTier.NORMAL
const val DOMAIN_BLOCKLIST_SETTINGS_DEFAULT_IS_THREAT_LIST_ENABLED = true

/**
 * On by default, unlike Gravatar and website icons, because nothing about the mail leaves the device: the lists are
 * downloaded whole and every lookup is made against the copy on the device. A match is shown as information beside the
 * sender or the link and never stops the user from opening either.
 *
 * @property tier the general blocklist: ads, trackers, telemetry and other junk, with some malware and phishing.
 * @property isThreatListEnabled whether HaGeZi's threat intelligence feeds - malware, phishing, scams - are also
 *   checked. Matched separately, and warned about more strongly, because that is what matters most in mail.
 */
data class DomainBlocklistSettings(
    val tier: DomainBlocklistTier = DOMAIN_BLOCKLIST_SETTINGS_DEFAULT_TIER,
    val isThreatListEnabled: Boolean = DOMAIN_BLOCKLIST_SETTINGS_DEFAULT_IS_THREAT_LIST_ENABLED,
)
