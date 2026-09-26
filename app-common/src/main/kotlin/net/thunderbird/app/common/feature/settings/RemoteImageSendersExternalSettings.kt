package net.thunderbird.app.common.feature.settings

import com.fsck.k9.preferences.ExternalGlobalSettings
import com.fsck.k9.preferences.ExternalSettingKeys.REMOTE_IMAGE_TRUSTED_DOMAINS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.REMOTE_IMAGE_TRUSTED_SENDERS_KEY
import com.fsck.k9.ui.messageview.RemoteImageScope
import com.fsck.k9.ui.messageview.RemoteImageSenderStore
import org.json.JSONArray

/**
 * Carries the senders and domains whose remote images the user chose to load through settings export.
 *
 * Each entry is a decision the user made about one sender, keyed by address or domain, so it means the same on any
 * device. Import adds to what is trusted here and never removes anything, since dropping an entry would only bring
 * a prompt back, while silently trusting would load tracking images the user never agreed to.
 */
internal class RemoteImageSendersExternalSettings(
    private val store: RemoteImageSenderStore,
) : ExternalGlobalSettings {

    override val keys: Set<String> = setOf(REMOTE_IMAGE_TRUSTED_SENDERS_KEY, REMOTE_IMAGE_TRUSTED_DOMAINS_KEY)

    override fun exportSettings(): Map<String, String> {
        val trusted = store.trusted()

        return mapOf(
            REMOTE_IMAGE_TRUSTED_SENDERS_KEY to trusted.valuesFor(RemoteImageScope.SENDER).toJson(),
            REMOTE_IMAGE_TRUSTED_DOMAINS_KEY to trusted.valuesFor(RemoteImageScope.DOMAIN).toJson(),
        )
    }

    override fun importSettings(values: Map<String, String>) {
        values[REMOTE_IMAGE_TRUSTED_SENDERS_KEY]?.parseValues()?.forEach { address ->
            store.trustStoredValue(RemoteImageScope.SENDER, address)
        }
        values[REMOTE_IMAGE_TRUSTED_DOMAINS_KEY]?.parseValues()?.forEach { domain ->
            store.trustStoredValue(RemoteImageScope.DOMAIN, domain)
        }
    }

    private fun List<Pair<RemoteImageScope, String>>.valuesFor(scope: RemoteImageScope): List<String> =
        filter { (entryScope, _) -> entryScope == scope }.map { (_, value) -> value }.sorted()
}

private fun List<String>.toJson(): String = JSONArray(this).toString()

/**
 * @return the values in [this], or `null` when it cannot be read - skipped rather than failing the whole import.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun String.parseValues(): List<String>? {
    if (isBlank()) return emptyList()

    return try {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    } catch (e: Exception) {
        null
    }
}
