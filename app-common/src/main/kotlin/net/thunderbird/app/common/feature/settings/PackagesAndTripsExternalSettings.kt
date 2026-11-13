package net.thunderbird.app.common.feature.settings

import com.fsck.k9.preferences.ExternalGlobalSettings
import com.fsck.k9.preferences.ExternalSettingKeys.PARCELS_MARKED_DELIVERED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.PARCELS_REMOVED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.TRAVEL_MAIL_MARKED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.TRAVEL_SENDERS_ADDED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.TRAVEL_SENDERS_REMOVED_KEY
import net.thunderbird.feature.packages.ParcelMarkSettings
import net.thunderbird.feature.packages.ParcelMarkSnapshot
import net.thunderbird.feature.trips.TravelSenderSettings
import net.thunderbird.feature.trips.TravelSenderSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Carries what the reader told Packages and Trips through settings export: parcels marked delivered or removed, and
 * changes to the travel sender list with the single mails marked as trip mail.
 *
 * Everything here is keyed by something that means the same on any device - a tracking number, a domain, a
 * Message-ID - so it applies as soon as the mail it is about is on the new device. Import adds to what is here.
 */
internal class PackagesAndTripsExternalSettings(
    private val parcelMarks: ParcelMarkSettings,
    private val travelSenders: TravelSenderSettings,
) : ExternalGlobalSettings {

    override val keys: Set<String> = setOf(
        PARCELS_MARKED_DELIVERED_KEY,
        PARCELS_REMOVED_KEY,
        TRAVEL_SENDERS_ADDED_KEY,
        TRAVEL_SENDERS_REMOVED_KEY,
        TRAVEL_MAIL_MARKED_KEY,
    )

    override fun exportSettings(): Map<String, String> {
        val marks = parcelMarks.exportMarks()
        val senders = travelSenders.exportSenders()

        return mapOf(
            PARCELS_MARKED_DELIVERED_KEY to marks.markedDelivered.toJson(),
            PARCELS_REMOVED_KEY to marks.removed.toJson(),
            TRAVEL_SENDERS_ADDED_KEY to senders.added.sorted().toJson(),
            TRAVEL_SENDERS_REMOVED_KEY to senders.removed.sorted().toJson(),
            TRAVEL_MAIL_MARKED_KEY to senders.markedMessageIds.toJson(),
        )
    }

    override fun importSettings(values: Map<String, String>) {
        val delivered = values[PARCELS_MARKED_DELIVERED_KEY]?.parseMarks().orEmpty()
        val removed = values[PARCELS_REMOVED_KEY]?.parseMarks().orEmpty()
        if (delivered.isNotEmpty() || removed.isNotEmpty()) {
            parcelMarks.importMarks(ParcelMarkSnapshot(markedDelivered = delivered, removed = removed))
        }

        val added = values[TRAVEL_SENDERS_ADDED_KEY]?.parseValues().orEmpty()
        val removedSenders = values[TRAVEL_SENDERS_REMOVED_KEY]?.parseValues().orEmpty()
        val marked = values[TRAVEL_MAIL_MARKED_KEY]?.parseValues().orEmpty()
        if (added.isNotEmpty() || removedSenders.isNotEmpty() || marked.isNotEmpty()) {
            travelSenders.importSenders(
                TravelSenderSnapshot(
                    added = added.toSet(),
                    removed = removedSenders.toSet(),
                    markedMessageIds = marked,
                ),
            )
        }
    }
}

private fun Collection<String>.toJson(): String = JSONArray(this).toString()

private fun Map<String, Long>.toJson(): String = JSONObject().apply {
    for ((key, value) in this@toJson.toSortedMap()) put(key, value)
}.toString()

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

/**
 * @return each name in [this] with its time, leaving out entries without a usable time, or `null` when it cannot be
 *   read at all.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun String.parseMarks(): Map<String, Long>? {
    if (isBlank()) return emptyMap()

    return try {
        val json = JSONObject(this)
        json.keys().asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { key -> json.optLong(key, -1L).takeIf { it > 0 }?.let { key to it } }
            .toMap()
    } catch (e: Exception) {
        null
    }
}
