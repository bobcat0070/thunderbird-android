package net.thunderbird.backend.graph.command

import net.thunderbird.backend.graph.api.FLAG_STATUS_FLAGGED
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.backend.graph.api.toFlags
import net.thunderbird.core.common.mail.Flag

private const val SEARCH_RESULT_LIMIT = 100

/**
 * The most matches a search across the whole mailbox asks for. More than one folder's worth, since they are shared
 * among all of them.
 */
private const val MAILBOX_SEARCH_RESULT_LIMIT = 250

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
     * Searches the whole mailbox in one request, where searching it folder by folder took one request per folder
     * and ran into Graph's throttling on a mailbox with many of them.
     *
     * @return the matches by the folder they are in, or `null` when there is no text to search for: a flag-only
     *   query is left to the folder-by-folder search, because Graph refuses some flag filters across the mailbox.
     */
    fun searchAllFolders(
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): Map<String, List<String>>? {
        if (query.isNullOrBlank()) return null

        // Graph expects the search term as a quoted string; embedded quotes would end it early.
        val sanitizedQuery = query.replace("\"", " ")
        val url = client.url("me/messages") {
            addQueryParameter("\$select", "id,parentFolderId,isRead,isDraft,flag")
            addQueryParameter("\$search", "\"$sanitizedQuery\"")
            addQueryParameter("\$top", MAILBOX_SEARCH_RESULT_LIMIT.toString())
        }

        return client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url)).value
            .filter { it.matchesFlags(requiredFlags, forbiddenFlags) }
            .mapNotNull { message -> message.parentFolderId?.let { folderId -> folderId to message.id } }
            .groupBy({ (folderId, _) -> folderId }, { (_, messageId) -> messageId })
    }

    /**
     * Finds a message by its RFC 5322 `Message-ID`, used to locate the server copy of a message the app already has.
     */
    fun findByMessageId(folderServerId: String, messageId: String): String? {
        // Single quotes terminate an OData string literal and are escaped by doubling them.
        val escapedMessageId = messageId.replace("'", "''")

        val url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
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

        val url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
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

        val url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
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
