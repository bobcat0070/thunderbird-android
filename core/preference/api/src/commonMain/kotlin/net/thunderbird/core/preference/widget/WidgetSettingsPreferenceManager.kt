package net.thunderbird.core.preference.widget

import net.thunderbird.core.preference.PreferenceManager

enum class WidgetSettingKey(val value: String) {

    ShowPersonal("widgetShowPersonal"),
    ShowNotifications("widgetShowNotifications"),
    ShowNewsletters("widgetShowNewsletters"),
}

interface WidgetSettingsPreferenceManager : PreferenceManager<WidgetSettings>
