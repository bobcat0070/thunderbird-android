package net.thunderbird.core.preference.gravatar

const val GRAVATAR_SETTINGS_DEFAULT_IS_ENABLED = false
const val GRAVATAR_SETTINGS_DEFAULT_API_KEY = ""

/**
 * Whether to fetch sender avatars from Gravatar, and the key to do it with.
 *
 * Off by default, and deliberately so: looking up an avatar tells gravatar.com that this device has mail from
 * that person. That is a reasonable trade for someone who wants sender pictures, but not one to make on their
 * behalf.
 *
 * @param apiKey optional. The avatar endpoint serves unauthenticated requests; a key raises the rate limit,
 *   which matters when a mailbox full of distinct senders scrolls past.
 */
data class GravatarSettings(
    val isEnabled: Boolean = GRAVATAR_SETTINGS_DEFAULT_IS_ENABLED,
    val apiKey: String = GRAVATAR_SETTINGS_DEFAULT_API_KEY,
)
