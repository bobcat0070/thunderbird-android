package net.thunderbird.feature.spamdigest.internal

import kotlin.time.Instant
import net.thunderbird.feature.spamdigest.SpamDigestHistory
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent

/**
 * How many events are kept: a few days of a digest that keeps failing, and weeks of one that works.
 */
internal const val MAX_RUN_EVENTS = 20

private const val FIELD_SEPARATOR = '|'
private const val EVENT_SEPARATOR = '\n'
private const val FIELD_COUNT = 3

/**
 * Records what happens to the digest, for [SpamDigestHistory].
 *
 * The debug log cannot answer why a digest did not arrive: it is off unless the reader turned it on, and it only
 * reaches back a few hours. This record is always kept, and small enough to show in the settings.
 */
internal interface SpamDigestRunLog : SpamDigestHistory {
    /**
     * @param detail must not name a person or a message; see [SpamDigestRunEvent].
     */
    fun record(kind: SpamDigestRunEvent.Kind, detail: String? = null)
}

/**
 * [events] with [event] added in front, keeping the newest [MAX_RUN_EVENTS].
 */
internal fun addRunEvent(events: List<SpamDigestRunEvent>, event: SpamDigestRunEvent): List<SpamDigestRunEvent> {
    return (listOf(event) + events).take(MAX_RUN_EVENTS)
}

/**
 * One event a line, as `millis|KIND|detail`.
 */
internal fun encodeRunEvents(events: List<SpamDigestRunEvent>): String {
    return events.joinToString(EVENT_SEPARATOR.toString()) { event ->
        val detail = event.detail.orEmpty().replace(EVENT_SEPARATOR, ' ')
        "${event.at.toEpochMilliseconds()}$FIELD_SEPARATOR${event.kind.name}$FIELD_SEPARATOR$detail"
    }
}

/**
 * Reads what [encodeRunEvents] wrote. A line that cannot be read - from a newer build, say - is skipped.
 */
internal fun decodeRunEvents(value: String?): List<SpamDigestRunEvent> {
    if (value.isNullOrEmpty()) return emptyList()

    return value.split(EVENT_SEPARATOR).mapNotNull { line ->
        val fields = line.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
        val millis = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val kind = SpamDigestRunEvent.Kind.entries.firstOrNull { it.name == fields.getOrNull(1) }
            ?: return@mapNotNull null

        SpamDigestRunEvent(Instant.fromEpochMilliseconds(millis), kind, fields.getOrNull(2)?.ifEmpty { null })
    }
}
