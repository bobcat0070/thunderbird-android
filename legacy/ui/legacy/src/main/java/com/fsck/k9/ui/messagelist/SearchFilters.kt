package com.fsck.k9.ui.messagelist

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.serialization.LocalMessageSearchSerializer

/**
 * How far back a search looks.
 */
enum class SearchPeriod(val duration: Duration?) {
    ANY_TIME(null),
    LAST_7_DAYS(7.days),
    LAST_30_DAYS(30.days),
    LAST_YEAR(365.days),
}

/**
 * What the chips above a search's results narrow it to, kept apart from what was searched for so they can be
 * changed, and saved with a search, without touching it.
 *
 * @param fromPeople only mail the app files as personal: from people the reader writes to or has as contacts,
 *   never newsletters or notifications.
 */
data class SearchFilters(
    val unread: Boolean = false,
    val starred: Boolean = false,
    val hasAttachment: Boolean = false,
    val fromPeople: Boolean = false,
    val period: SearchPeriod = SearchPeriod.ANY_TIME,
) {
    val isEmpty: Boolean
        get() = this == SearchFilters()

    /**
     * The filters as a short string, for an intent or saved preferences: the names of those switched on and the
     * period, separated by commas.
     */
    fun encode(): String = buildList {
        if (unread) add(UNREAD)
        if (starred) add(STARRED)
        if (hasAttachment) add(ATTACHMENT)
        if (fromPeople) add(PEOPLE)
        if (period != SearchPeriod.ANY_TIME) add(period.name)
    }.joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = ","
        private const val UNREAD = "unread"
        private const val STARRED = "starred"
        private const val ATTACHMENT = "attachment"
        private const val PEOPLE = "people"

        /**
         * Reads [encode]'s string back; anything it does not know is left out.
         */
        fun decode(value: String?): SearchFilters {
            val parts = value.orEmpty().split(SEPARATOR).map { it.trim() }.toSet()
            return SearchFilters(
                unread = UNREAD in parts,
                starred = STARRED in parts,
                hasAttachment = ATTACHMENT in parts,
                fromPeople = PEOPLE in parts,
                period = SearchPeriod.entries.firstOrNull { it.name in parts } ?: SearchPeriod.ANY_TIME,
            )
        }
    }
}

/**
 * A copy of this search narrowed by [filters]; the search itself is left as it is.
 *
 * @param now the time a period counts back from, in milliseconds since the epoch.
 */
fun LocalMessageSearch.withFilters(filters: SearchFilters, now: Long): LocalMessageSearch {
    val copy = LocalMessageSearchSerializer.deserialize(LocalMessageSearchSerializer.serialize(this))
    if (filters.unread) copy.and(MessageSearchField.READ, "0", SearchAttribute.EQUALS)
    if (filters.starred) copy.and(MessageSearchField.FLAGGED, "1", SearchAttribute.EQUALS)
    if (filters.hasAttachment) copy.and(MessageSearchField.ATTACHMENT_COUNT, "0", SearchAttribute.NOT_EQUALS)
    if (filters.fromPeople) copy.and(MessageSearchField.CLASSIFICATION, MessageClass.HUMAN.name, SearchAttribute.EQUALS)
    filters.period.duration?.let { duration ->
        copy.and(MessageSearchField.DATE, (now - duration.inWholeMilliseconds).toString(), SearchAttribute.AT_LEAST)
    }
    return copy
}
