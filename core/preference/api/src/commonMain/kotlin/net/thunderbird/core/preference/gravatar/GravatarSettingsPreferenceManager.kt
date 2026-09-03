package net.thunderbird.core.preference.gravatar

import net.thunderbird.core.preference.PreferenceManager

enum class GravatarSettingKey(val value: String) {

    Enabled("gravatarEnabled"),
    ApiKey("gravatarApiKey"),
}

interface GravatarSettingsPreferenceManager : PreferenceManager<GravatarSettings>
