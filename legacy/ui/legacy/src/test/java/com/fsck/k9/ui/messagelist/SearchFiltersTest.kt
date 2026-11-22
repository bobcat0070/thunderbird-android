package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.time.Duration.Companion.days
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import org.junit.Test

private const val NOW = 1_795_000_000_000L

class SearchFiltersTest {

    @Test
    fun `filters should survive being written down and read back`() {
        val testSubject = SearchFilters(unread = true, hasAttachment = true, period = SearchPeriod.LAST_30_DAYS)

        val result = SearchFilters.decode(testSubject.encode())

        assertThat(result).isEqualTo(testSubject)
    }

    @Test
    fun `nothing written down should mean no filters`() {
        assertThat(SearchFilters.decode(null).isEmpty).isTrue()
        assertThat(SearchFilters.decode("").isEmpty).isTrue()
        assertThat(SearchFilters.decode("unknown,LAST_CENTURY").isEmpty).isTrue()
    }

    @Test
    fun `each filter should narrow the search by its own condition`() {
        val testSubject = search()

        val result = testSubject.withFilters(
            SearchFilters(true, true, true, true, SearchPeriod.LAST_7_DAYS),
            now = NOW,
        )

        assertThat(result.leafSet.mapNotNull { it.condition }).containsOnly(
            SearchCondition(MessageSearchField.SUBJECT, SearchAttribute.CONTAINS, "invoice"),
            SearchCondition(MessageSearchField.READ, SearchAttribute.EQUALS, "0"),
            SearchCondition(MessageSearchField.FLAGGED, SearchAttribute.EQUALS, "1"),
            SearchCondition(MessageSearchField.ATTACHMENT_COUNT, SearchAttribute.NOT_EQUALS, "0"),
            SearchCondition(MessageSearchField.CLASSIFICATION, SearchAttribute.EQUALS, "HUMAN"),
            SearchCondition(
                MessageSearchField.DATE,
                SearchAttribute.AT_LEAST,
                (NOW - 7.days.inWholeMilliseconds).toString(),
            ),
        )
    }

    @Test
    fun `filtering should leave the original search as it was`() {
        val testSubject = search()

        testSubject.withFilters(SearchFilters(unread = true), now = NOW)

        assertThat(testSubject.leafSet.mapNotNull { it.condition }.filter { it.field == MessageSearchField.READ })
            .isEmpty()
    }

    private fun search() = LocalMessageSearch().apply {
        isManualSearch = true
        or(SearchCondition(MessageSearchField.SUBJECT, SearchAttribute.CONTAINS, "invoice"))
    }
}
