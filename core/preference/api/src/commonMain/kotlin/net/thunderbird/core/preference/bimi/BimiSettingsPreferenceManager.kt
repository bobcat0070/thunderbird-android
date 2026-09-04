package net.thunderbird.core.preference.bimi

import net.thunderbird.core.preference.PreferenceManager

enum class BimiSettingKey(val value: String) {

    Enabled("bimiEnabled"),
}

interface BimiSettingsPreferenceManager : PreferenceManager<BimiSettings>
