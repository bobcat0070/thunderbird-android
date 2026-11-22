package net.thunderbird.app.common.feature.settings

import com.fsck.k9.preferences.ExternalGlobalSettings
import com.fsck.k9.preferences.ExternalSettingKeys.UNSUBSCRIBED_SENDERS_KEY
import net.thunderbird.feature.newsletter.UnsubscribeHistorySettings
import org.json.JSONObject

/**
 * Carries the senders the reader unsubscribed from through settings export, with when they did, so mail that keeps
 * coming is still pointed out on a new device. Import adds to what is here.
 */
internal class UnsubscribeExternalSettings(
    private val settings: UnsubscribeHistorySettings,
) : ExternalGlobalSettings {

    override val keys: Set<String> = setOf(UNSUBSCRIBED_SENDERS_KEY)

    override fun exportSettings(): Map<String, String> {
        val unsubscribes = JSONObject().apply {
            for ((address, at) in settings.exportUnsubscribes().toSortedMap()) put(address, at)
        }

        return mapOf(UNSUBSCRIBED_SENDERS_KEY to unsubscribes.toString())
    }

    override fun importSettings(values: Map<String, String>) {
        val unsubscribes = values[UNSUBSCRIBED_SENDERS_KEY]?.parseUnsubscribes().orEmpty()
        if (unsubscribes.isEmpty()) return

        settings.importUnsubscribes(unsubscribes)
    }
}

/**
 * @return when each address in [this] was unsubscribed from, or `null` when it cannot be read - skipped rather than
 *   failing the whole import.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun String.parseUnsubscribes(): Map<String, Long>? {
    if (isBlank()) return emptyMap()

    return try {
        val json = JSONObject(this)
        json.keys().asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { address -> json.optLong(address, 0L).takeIf { it > 0 }?.let { address to it } }
            .toMap()
    } catch (e: Exception) {
        null
    }
}
