package net.thunderbird.core.preference.blocklist

import net.thunderbird.core.preference.PreferenceManager

enum class DomainBlocklistSettingKey(val value: String) {
    Tier("domainBlocklistTier"),
    ThreatListEnabled("domainBlocklistThreatListEnabled"),
}

interface DomainBlocklistSettingsPreferenceManager : PreferenceManager<DomainBlocklistSettings>
