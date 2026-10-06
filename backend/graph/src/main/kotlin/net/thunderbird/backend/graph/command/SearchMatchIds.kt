package net.thunderbird.backend.graph.command

import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage

/**
 * How many `Message-ID`s one request looks up again; each is a clause of the filter, and the filter is part of the
 * URL.
 */
private const val ID_LOOKUP_BATCH_SIZE = 10

/**
 * Room in a lookup's answer for copies of a message: one in Sent and one in the inbox share a `Message-ID`.
 */
private const val COPIES_PER_MESSAGE_ID = 4

/**
 * What a text search selects: the id and folder to save the match under, and what to look it up again by.
 */
internal const val TEXT_SEARCH_SELECT = "id,parentFolderId,internetMessageId,isRead,isDraft,flag"

/**
 * Gives the matches of a `$search` the ids the app stores mail under.
 *
 * `$search` answers with Graph's default ids however it is asked, while everything the app synchronizes is stored
 * under immutable ids: saved as they come, the matches already on the device would all be saved a second time, and
 * the rest again by the next sync. A listing does honour the preference, so the matches are listed again by their
 * `Message-ID`, a few at a time.
 */
internal class SearchMatchIds(
    private val client: GraphApiClient,
) {
    /**
     * @return the same messages under their immutable ids. A match with no `Message-ID`, or not found again, is
     *   left out rather than saved under an id nothing else uses.
     */
    fun withImmutableIds(matches: List<GraphMessage>): List<GraphMessage> {
        val wanted = matches.mapNotNullTo(mutableSetOf()) { message ->
            message.internetMessageId?.let { messageId -> messageId to message.parentFolderId }
        }

        return wanted.map { (messageId, _) -> messageId }
            .distinct()
            .chunked(ID_LOOKUP_BATCH_SIZE)
            .flatMap(::listByMessageIds)
            .filter { message -> (message.internetMessageId to message.parentFolderId) in wanted }
    }

    private fun listByMessageIds(messageIds: List<String>): List<GraphMessage> {
        // Single quotes terminate an OData string literal and are escaped by doubling them.
        val filter = messageIds.joinToString(" or ") { messageId ->
            "internetMessageId eq '${messageId.replace("'", "''")}'"
        }
        val url = client.url("me/messages") {
            addQueryParameter("\$select", TEXT_SEARCH_SELECT)
            addQueryParameter("\$filter", filter)
            addQueryParameter("\$top", (messageIds.size * COPIES_PER_MESSAGE_ID).toString())
        }

        return client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url)).value
    }
}
