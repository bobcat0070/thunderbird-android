package net.thunderbird.feature.blocklist.internal

import kotlin.concurrent.Volatile
import net.thunderbird.core.preference.blocklist.DomainBlocklistSettings
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.blocklist.BlocklistMatch
import net.thunderbird.feature.blocklist.DomainBlocklist

/**
 * The user's blocklist choice, read each time a domain is looked up so a change applies at once.
 */
fun interface BlocklistSettingsProvider {
    fun settings(): DomainBlocklistSettings
}

/**
 * Answers from the lists loaded into memory, which [DefaultBlocklistUpdater] puts there after reading them from the
 * device or downloading them.
 */
class DefaultDomainBlocklist internal constructor(
    private val settingsProvider: BlocklistSettingsProvider,
) : DomainBlocklist {

    @Volatile
    private var loaded: Map<HagezeiList, HashedDomainList> = emptyMap()

    internal fun replace(lists: Map<HagezeiList, HashedDomainList>) {
        loaded = lists
    }

    internal fun loadedLists(): Map<HagezeiList, HashedDomainList> = loaded

    override fun match(host: String): BlocklistMatch? {
        val lists = loaded
        if (lists.isEmpty()) return null

        val settings = settingsProvider.settings()
        val domains = host.trim().trimEnd('.').lowercase().substringAfterLast('@').andParents()
        if (domains.isEmpty()) return null

        val threats = lists[HagezeiList.THREATS].takeIf { settings.isThreatListEnabled }
        threats?.let { list -> domains.firstOrNull { it in list } }?.let { listed ->
            return BlocklistMatch(listed, BlocklistKind.THREAT, HagezeiList.THREATS.displayName)
        }

        val tier = HagezeiList.forTier(settings.tier) ?: return null
        val general = lists[tier] ?: return null
        return domains.firstOrNull { it in general }?.let { listed ->
            BlocklistMatch(listed, BlocklistKind.GENERAL, tier.displayName)
        }
    }
}

/**
 * @return this host and each domain it is under, down to two labels: `a.b.example.com`, `b.example.com`,
 *   `example.com`. A list entry covers its subdomains, so any of these being listed lists the host.
 */
internal fun String.andParents(): List<String> {
    val labels = split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() }) return emptyList()

    return (0..labels.size - 2).map { start -> labels.drop(start).joinToString(".") }
}
