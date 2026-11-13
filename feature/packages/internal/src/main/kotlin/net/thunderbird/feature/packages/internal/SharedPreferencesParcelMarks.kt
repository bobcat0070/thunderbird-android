package net.thunderbird.feature.packages.internal

import android.content.Context
import androidx.core.content.edit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.thunderbird.feature.packages.ParcelMarkSettings
import net.thunderbird.feature.packages.ParcelMarkSnapshot

/**
 * How long the reader's word on a parcel is kept: longer than any parcel stays in the list.
 */
private const val MARK_KEPT_MILLIS = 90L * 24 * 60 * 60 * 1000

/**
 * What a tracking number of any recognised carrier looks like; an imported mark naming anything else is dropped.
 */
private val TRACKING_NUMBER = Regex("[A-Za-z0-9]{6,40}")

/**
 * Keeps the parcels the reader marked delivered or removed, each with when, in the app's private preferences.
 */
@OptIn(ExperimentalTime::class)
internal class SharedPreferencesParcelMarks(
    context: Context,
    private val clock: Clock,
) : ParcelMarks, ParcelMarkSettings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun markedDelivered(): Set<String> = read(DELIVERED)

    override fun removed(): Set<String> = read(REMOVED)

    override fun setMarkedDelivered(number: String, isDelivered: Boolean) {
        update(DELIVERED) { marks -> if (isDelivered) marks + (number to now()) else marks - number }
    }

    override fun remove(number: String) {
        update(REMOVED) { marks -> marks + (number to now()) }
    }

    override fun exportMarks(): ParcelMarkSnapshot =
        ParcelMarkSnapshot(markedDelivered = entries(DELIVERED), removed = entries(REMOVED))

    override fun importMarks(marks: ParcelMarkSnapshot) {
        merge(DELIVERED, marks.markedDelivered)
        merge(REMOVED, marks.removed)
    }

    private fun merge(key: String, imported: Map<String, Long>) {
        // A mark from the future would never expire.
        val latest = now()
        val valid = imported.filter { (number, markedAt) -> TRACKING_NUMBER.matches(number) && markedAt <= latest }
        if (valid.isEmpty()) return

        update(key) { marks ->
            marks + valid.mapValues { (number, markedAt) -> maxOf(markedAt, marks[number] ?: markedAt) }
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun read(key: String): Set<String> = entries(key).keys

    private fun entries(key: String): Map<String, Long> {
        val oldest = now() - MARK_KEPT_MILLIS
        return preferences.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val number = entry.substringBefore(SEPARATOR)
                val markedAt = entry.substringAfter(SEPARATOR, "").toLongOrNull() ?: return@mapNotNull null
                (number to markedAt).takeIf { markedAt >= oldest }
            }
            .toMap()
    }

    private fun update(key: String, change: (Map<String, Long>) -> Map<String, Long>) {
        val marks = change(entries(key))
        preferences.edit {
            putStringSet(key, marks.map { (number, markedAt) -> "$number$SEPARATOR$markedAt" }.toSet())
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "packages"
        const val DELIVERED = "marked_delivered"
        const val REMOVED = "removed"
        const val SEPARATOR = "|"
    }
}
