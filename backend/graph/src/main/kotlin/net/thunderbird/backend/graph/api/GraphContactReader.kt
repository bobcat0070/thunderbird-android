package net.thunderbird.backend.graph.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val CONTACT_SELECT = "id,displayName,emailAddresses"
private const val CONTACT_PAGE_SIZE = 100

/**
 * How many pages one round of contact reading will walk.
 *
 * A first read of a large address book is paged, and this bounds what one sync will do rather than holding the
 * connection until thousands of contacts have been read. Whatever is left is picked up by the next round, which
 * resumes from the delta link this one stored.
 */
private const val MAX_CONTACT_PAGES = 50

private const val PEOPLE_SELECT = "displayName,scoredEmailAddresses"

/**
 * How many directory matches one search returns. This is a dropdown under a text field, not a report.
 */
private const val PEOPLE_PAGE_SIZE = 15

/**
 * One contact, as this backend cares about it: a name and the addresses it can be written to.
 */
internal data class GraphContact(
    val id: String,
    val displayName: String?,
    val addresses: List<String>,
    /**
     * Whether Graph reported this contact as deleted rather than changed. A delta round reports removals the
     * same way it reports changes, and treating one as the other would keep completing an address the user has
     * deleted from their address book.
     */
    val isRemoved: Boolean,
)

/**
 * What one round of reading contacts found, and where the next round should resume from.
 */
internal data class GraphContactRound(
    val contacts: List<GraphContact>,
    val deltaLink: String?,
)

/**
 * Reads the mailbox's own contacts, and searches the organisation's directory.
 *
 * These are two different questions and use two different endpoints. `/me/contacts` is the user's address book,
 * which is small, theirs, and worth holding locally so completion works offline and costs nothing per keystroke.
 * `/me/people` is Microsoft's ranked view of everyone the user deals with, including colleagues who are in the
 * organisation's directory but not in the address book - useful, but a search of it is a request per query, so it
 * is asked only when the user has asked for it.
 *
 * Contacts are read incrementally. Graph's delta endpoint returns a link that names the state of the address book,
 * and handing it back next time asks only what changed since - which is what keeps a sync cheap once the first one
 * has happened.
 */
internal class GraphContactReader(
    private val client: GraphApiClient,
) {
    /**
     * Reads contacts, from scratch or from where the last round finished.
     *
     * @param deltaLink what the previous round returned, or `null` to read the whole address book.
     */
    fun readContacts(deltaLink: String?): GraphContactRound {
        var url = if (deltaLink != null) {
            client.absoluteUrl(deltaLink)
        } else {
            client.url("me/contacts/delta") {
                addQueryParameter("\$select", CONTACT_SELECT)
                addQueryParameter("\$top", CONTACT_PAGE_SIZE.toString())
            }
        }

        val contacts = mutableListOf<GraphContact>()
        var page = 0

        while (page < MAX_CONTACT_PAGES) {
            val collection = client.json.decodeFromString<GraphCollection<GraphContactDto>>(client.getString(url))
            contacts += collection.value.map { it.toContact() }

            val nextLink = collection.nextLink
            if (nextLink == null) {
                return GraphContactRound(contacts, collection.deltaLink)
            }

            url = client.absoluteUrl(nextLink)
            page++
        }

        // Out of pages rather than out of contacts: no delta link is returned, so the next round starts again
        // from where this one began rather than believing it has seen everything.
        return GraphContactRound(contacts, deltaLink = null)
    }

    /**
     * Searches the people Graph associates with this user, which includes the organisation's directory.
     *
     * Each address carries Graph's own relevance score, and the ordering it returns is that ranking, so it is
     * kept as it arrives rather than re-sorted here.
     */
    fun searchPeople(query: String): List<GraphContact> {
        val url = client.url("me/people") {
            // Quoted because Graph's search syntax expects a phrase, and a bare term with a space in it is a
            // syntax error rather than a search for two words.
            addQueryParameter("\$search", "\"$query\"")
            addQueryParameter("\$select", PEOPLE_SELECT)
            addQueryParameter("\$top", PEOPLE_PAGE_SIZE.toString())
        }

        val collection = client.json.decodeFromString<GraphCollection<GraphPersonDto>>(client.getString(url))

        return collection.value.map { person ->
            GraphContact(
                id = person.id ?: person.displayName.orEmpty(),
                displayName = person.displayName,
                addresses = person.scoredEmailAddresses.mapNotNull { it.address },
                isRemoved = false,
            )
        }
    }
}

@Serializable
internal data class GraphContactDto(
    val id: String = "",
    val displayName: String? = null,
    val emailAddresses: List<GraphEmailAddressDto> = emptyList(),
    @SerialName("@removed") val removed: GraphRemovedDto? = null,
)

@Serializable
internal data class GraphEmailAddressDto(
    val address: String? = null,
    val name: String? = null,
)

@Serializable
internal data class GraphRemovedDto(
    val reason: String? = null,
)

@Serializable
internal data class GraphPersonDto(
    val id: String? = null,
    val displayName: String? = null,
    val scoredEmailAddresses: List<GraphScoredEmailAddressDto> = emptyList(),
)

@Serializable
internal data class GraphScoredEmailAddressDto(
    val address: String? = null,
    val relevanceScore: Double? = null,
)

private fun GraphContactDto.toContact(): GraphContact = GraphContact(
    id = id,
    displayName = displayName,
    addresses = emailAddresses.mapNotNull { it.address },
    isRemoved = removed != null,
)
