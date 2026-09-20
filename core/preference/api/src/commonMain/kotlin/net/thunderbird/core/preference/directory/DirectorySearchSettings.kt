package net.thunderbird.core.preference.directory

const val DIRECTORY_SEARCH_SETTINGS_DEFAULT_IS_ENABLED = false

/**
 * Whether addressing a message may search the organisation's directory.
 *
 * Completion is answered from what is held on the device: the device's own contacts, the people this mailbox has
 * written to, and the address book fetched from the account. A directory search goes further and asks the
 * provider about a name the device has never seen.
 *
 * Off by default, for the same reason Gravatar and website icons are: it sends what somebody is typing to a
 * third party, which is a reasonable trade for a person who wants to complete colleagues they have never written
 * to and a poor one to make on their behalf. Local completion keeps working either way.
 */
data class DirectorySearchSettings(
    val isEnabled: Boolean = DIRECTORY_SEARCH_SETTINGS_DEFAULT_IS_ENABLED,
)
