package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class SavedSearchStoreTest : RobolectricTest() {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `saved searches should be kept in order`() {
        val testSubject = SharedPreferencesSavedSearchStore(context)
        val filters = SearchFilters(unread = true, period = SearchPeriod.LAST_30_DAYS)

        val first = testSubject.add("Invoices", "invoice", accountId = null, folderId = null, filters = filters)
        val second = testSubject.add("Dana", "dana", accountId = "account-1", folderId = 7L, filters = SearchFilters())

        assertThat(SharedPreferencesSavedSearchStore(context).savedSearches.value).containsExactly(first, second)
    }

    @Test
    fun `a removed search should be gone`() {
        val testSubject = SharedPreferencesSavedSearchStore(context)
        val saved = testSubject.add("Invoices", "invoice", null, null, SearchFilters())

        testSubject.remove(saved.id)

        assertThat(testSubject.savedSearches.value).isEmpty()
        assertThat(SharedPreferencesSavedSearchStore(context).savedSearches.value).isEmpty()
    }
}
