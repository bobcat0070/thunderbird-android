package net.thunderbird.core.preference.directory

import net.thunderbird.core.preference.PreferenceManager

enum class DirectorySearchSettingKey(val value: String) {

    Enabled("directorySearchEnabled"),
}

interface DirectorySearchSettingsPreferenceManager : PreferenceManager<DirectorySearchSettings>
