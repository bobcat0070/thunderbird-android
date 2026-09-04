package net.thunderbird.backend.graph.command

import net.thunderbird.backend.graph.api.FLAG_STATUS_FLAGGED
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.toFlags
import net.thunderbird.core.common.mail.Flag

private const val SEARCH_RESULT_LIMIT = 100

/**
 * Searches messages on the server.
 *
 * Graph does not allow `$search` to be combined with `$filter` or `$orderby`, so a full-text query and a flag-only
 * query are issued as two different request shapes. When both are requested, the text search runs on the server and
 * the flag conditions are applied to its results.
 */
internal class CommandSearch(
    private val client: GraphApiClient,
) {
    fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): List<String> {
        val messages = if (query.isNullOrBlank()) {
            searchByFlags(folderServerId, requiredFlags, forbiddenFlags)
        } else {
            searchByText(folderServerId, query)
                .filter { it.matchesFlags(requiredFlags, forbiddenFlags) }
        }

        return messages.map { it.id }
    }

    /**
     * Finds a message by its RFC 5322 `Message-ID`, used to locate the server copy of a message the app already has.
     */
    fun findByMessageId(folderServerId: String, messageId: String): String? {
        // Single quotes terminate an OData string literal and are escaped by doubling them.
        val escapedMessageId = messageId.replace("'", "''")

        val url = client.url("me/mailFolders/$folderServerId/messages") {
            addQueryParameter("\$select", "id")
            addQueryParameter("\$filter", "internetMessageId eq '$escapedMessageId'")
            addQueryParameter("\$top", "1")
        }

        return client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))
            .value
            .firstOrNull()
            ?.id
    }

    private fun searchByText(folderServerId: String, query: String): List<GraphMessage> {
        // Graph expects the search term as a quoted string; embedded quotes would end it early.
        val sanitizedQuery = query.replace("\"", " ")

        val url = client.url("me/mailFolders/$folderServerId/messages") {
            addQueryParameter("\$select", "id,isRead,isDraft,flag")
            addQueryParameter("\$search", "\"$sanitizedQuery\"")
            addQueryParameter("\$top", SEARCH_RESULT_LIMIT.toString())
        }

        return client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url)).value
    }

    private fun searchByFlags(
        folderServerId: String,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): List<GraphMessage> {
        val filter = buildFilter(requiredFlags, forbiddenFlags)

        val url = client.url("me/mailFolders/$folderServerId/messages") {
            addQueryParameter("\$select", "id,isRead,isDraft,flag")
            addQueryParameter("\$orderby", "receivedDateTime desc")
            addQueryParameter("\$top", SEARCH_RESULT_LIMIT.toString())

            if (filter != null) {
                addQueryParameter("\$filter", filter)
            }
        }

        return client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url)).value
    }

    /**
     * Builds an OData filter for the flags Graph can express, or `null` when none of them apply.
     */
    private fun buildFilter(requiredFlags: Set<Flag>?, forbiddenFlags: Set<Flag>?): String? {
        val conditions = buildList {
            if (requiredFlags?.contains(Flag.SEEN) == true) add("isRead eq true")
            if (forbiddenFlags?.contains(Flag.SEEN) == true) add("isRead eq false")
            if (requiredFlags?.contains(Flag.FLAGGED) == true) add("flag/flagStatus eq '$FLAG_STATUS_FLAGGED'")
            if (forbiddenFlags?.contains(Flag.FLAGGED) == true) add("flag/flagStatus ne '$FLAG_STATUS_FLAGGED'")
        }

        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" and ")
    }

    private fun GraphMessage.matchesFlags(requiredFlags: Set<Flag>?, forbiddenFlags: Set<Flag>?): Boolean {
        val flags = toFlags()

        return requiredFlags.orEmpty().all { it in flags } && forbiddenFlags.orEmpty().none { it in flags }
    }
}
