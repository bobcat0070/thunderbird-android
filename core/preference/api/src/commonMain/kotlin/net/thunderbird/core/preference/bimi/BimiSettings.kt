package net.thunderbird.core.preference.bimi

const val BIMI_SETTINGS_DEFAULT_IS_ENABLED = false

/**
 * Whether to show sender domains' verified brand indicators.
 *
 * Off by default. Looking one up asks DNS about the sender's domain and then fetches a logo from that
 * domain's server, so it tells both who this device gets mail from. That is a reasonable trade for someone
 * who wants brand logos, but not one to make on their behalf.
 */
data class BimiSettings(
    val isEnabled: Boolean = BIMI_SETTINGS_DEFAULT_IS_ENABLED,
)
