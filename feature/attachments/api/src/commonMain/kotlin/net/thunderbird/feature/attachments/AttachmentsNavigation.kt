package net.thunderbird.feature.attachments

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val ATTACHMENTS_BASE_DEEP_LINK = "app://feature/attachments"

/**
 * Characters an email address may hold that would end or change a path segment of the route.
 */
private val PATH_RESERVED = setOf('%', '/', '?', '#', ' ')

sealed interface AttachmentsRoute : Route {
    /**
     * Every file [address] has sent the reader, across accounts, newest first.
     */
    @Serializable
    data class Sender(val address: String) : AttachmentsRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = "$basePath/${encodePathSegment(address)}"

        companion object {
            const val BASE_PATH = "$ATTACHMENTS_BASE_DEEP_LINK/sender"
        }
    }
}

interface AttachmentsNavigation : Navigation<AttachmentsRoute>

/**
 * Percent-encodes the few characters that would break the address out of its path segment; navigation decodes the
 * segment again before handing the address to the screen.
 */
private fun encodePathSegment(value: String): String = buildString {
    for (char in value) {
        if (char in PATH_RESERVED) {
            append('%').append(char.code.toString(HEX_RADIX).uppercase().padStart(2, '0'))
        } else {
            append(char)
        }
    }
}

private const val HEX_RADIX = 16
