package net.thunderbird.app.common.feature.settings

import com.fsck.k9.preferences.ExternalGlobalSettings
import com.fsck.k9.preferences.ExternalSettingKeys.PLUS_ADDRESS_ALLOWED_SENDERS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.PLUS_ADDRESS_IGNORED_KEY
import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import net.thunderbird.feature.plusaddress.PlusAddressSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Carries what the reader said about their plus addresses through settings export: the senders they said are fine
 * for an address, and the addresses they stopped checking.
 *
 * Both are keyed by the address itself, which means the same on any device. Import adds to what is here.
 */
internal class PlusAddressExternalSettings(
    private val settings: PlusAddressSettings,
) : ExternalGlobalSettings {

    override val keys: Set<String> = setOf(PLUS_ADDRESS_ALLOWED_SENDERS_KEY, PLUS_ADDRESS_IGNORED_KEY)

    override fun exportSettings(): Map<String, String> {
        val decisions = settings.exportDecisions()

        val allowed = JSONObject().apply {
            for ((address, domains) in decisions.allowedSenders.toSortedMap()) put(address, JSONArray(domains.sorted()))
        }

        return mapOf(
            PLUS_ADDRESS_ALLOWED_SENDERS_KEY to allowed.toString(),
            PLUS_ADDRESS_IGNORED_KEY to JSONArray(decisions.ignored.sorted()).toString(),
        )
    }

    override fun importSettings(values: Map<String, String>) {
        val allowed = values[PLUS_ADDRESS_ALLOWED_SENDERS_KEY]?.parseAllowed().orEmpty()
        val ignored = values[PLUS_ADDRESS_IGNORED_KEY]?.parseValues().orEmpty()
        if (allowed.isEmpty() && ignored.isEmpty()) return

        settings.importDecisions(PlusAddressDecisionSnapshot(allowedSenders = allowed, ignored = ignored.toSet()))
    }
}

/**
 * @return the values in [this], or `null` when it cannot be read - skipped rather than failing the whole import.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun String.parseValues(): List<String>? {
    if (isBlank()) return emptyList()

    return try {
        JSONArray(this).strings()
    } catch (e: Exception) {
        null
    }
}

/**
 * @return the domains listed for each address in [this], or `null` when it cannot be read.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun String.parseAllowed(): Map<String, Set<String>>? {
    if (isBlank()) return emptyMap()

    return try {
        val json = JSONObject(this)
        json.keys().asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { address ->
                json.optJSONArray(address)?.strings()?.toSet()?.takeIf { it.isNotEmpty() }?.let { address to it }
            }
            .toMap()
    } catch (e: Exception) {
        null
    }
}

private fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
