package com.fsck.k9.ui.messagelist

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A search kept in the drawer: what was searched for, where, and the chips that narrowed it.
 *
 * Kept as the question rather than as a finished search, so a period like "last 30 days" counts back from the day it
 * is opened, not from the day it was saved.
 *
 * @param accountId the account searched, or `null` for every account.
 * @param folderId the folder searched, or `null` for every folder of [accountId].
 */
data class SavedSearch(
    val id: String,
    val name: String,
    val query: String,
    val accountId: String?,
    val folderId: Long?,
    val filters: SearchFilters,
)

/**
 * The searches the reader saved to the drawer, in the order they were saved.
 */
interface SavedSearchStore {
    val savedSearches: StateFlow<List<SavedSearch>>

    fun add(name: String, query: String, accountId: String?, folderId: Long?, filters: SearchFilters): SavedSearch

    fun remove(id: String)
}

/**
 * Keeps saved searches in the app's private preferences, as one JSON list.
 */
internal class SharedPreferencesSavedSearchStore(context: Context) : SavedSearchStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val searches = MutableStateFlow(read())

    override val savedSearches: StateFlow<List<SavedSearch>> = searches.asStateFlow()

    @Synchronized
    override fun add(
        name: String,
        query: String,
        accountId: String?,
        folderId: Long?,
        filters: SearchFilters,
    ): SavedSearch {
        val search = SavedSearch(UUID.randomUUID().toString(), name.trim(), query, accountId, folderId, filters)
        write(searches.value + search)
        return search
    }

    @Synchronized
    override fun remove(id: String) {
        write(searches.value.filterNot { it.id == id })
    }

    private fun write(list: List<SavedSearch>) {
        val json = JSONArray()
        for (search in list) {
            json.put(
                JSONObject()
                    .put(ID, search.id)
                    .put(NAME, search.name)
                    .put(QUERY, search.query)
                    .putOpt(ACCOUNT, search.accountId)
                    .putOpt(FOLDER, search.folderId)
                    .put(FILTERS, search.filters.encode()),
            )
        }
        preferences.edit { putString(SEARCHES, json.toString()) }
        searches.value = list
    }

    private fun read(): List<SavedSearch> {
        val json = preferences.getString(SEARCHES, null)?.let { text ->
            try {
                JSONArray(text)
            } catch (_: JSONException) {
                null
            }
        } ?: return emptyList()

        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString(ID).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val query = item.optString(QUERY).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SavedSearch(
                id = id,
                name = item.optString(NAME).ifEmpty { query },
                query = query,
                accountId = item.optString(ACCOUNT).takeIf { it.isNotEmpty() },
                folderId = if (item.has(FOLDER)) item.optLong(FOLDER) else null,
                filters = SearchFilters.decode(item.optString(FILTERS)),
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "saved_searches"
        const val SEARCHES = "searches"
        const val ID = "id"
        const val NAME = "name"
        const val QUERY = "query"
        const val ACCOUNT = "account"
        const val FOLDER = "folder"
        const val FILTERS = "filters"
    }
}

/**
 * The search a query typed into the search box runs: the query anywhere in the senders, recipients, subject or text.
 */
fun manualSearchFor(query: String): LocalMessageSearch = LocalMessageSearch().apply {
    isManualSearch = true
    for (field in QUERY_FIELDS) {
        or(SearchCondition(field, SearchAttribute.CONTAINS, query))
    }
}

private val QUERY_FIELDS = listOf(
    MessageSearchField.SENDER,
    MessageSearchField.TO,
    MessageSearchField.CC,
    MessageSearchField.BCC,
    MessageSearchField.SUBJECT,
    MessageSearchField.MESSAGE_CONTENTS,
)
