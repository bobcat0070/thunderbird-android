package net.thunderbird.core.preference.websiteicon

import net.thunderbird.core.preference.PreferenceManager

enum class WebsiteIconSettingKey(val value: String) {

    Enabled("websiteIconEnabled"),
}

interface WebsiteIconSettingsPreferenceManager : PreferenceManager<WebsiteIconSettings>
