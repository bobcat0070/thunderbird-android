package net.thunderbird.backend.graph.command

import java.util.Date
import kotlinx.serialization.json.decodeFromJsonElement
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphExtendedProperty
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.MESSAGE_ENVELOPE_EXPAND
import net.thunderbird.backend.graph.api.batchGet
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.backend.graph.api.receivedDate
import net.thunderbird.core.logging.Logger
import okhttp3.HttpUrl

/**
 * Up to this many messages, each is looked up on its own; beyond it, the folder is listed. An incremental round is a
 * handful of messages, an initial one up to thousands.
 */
private const val MAX_MESSAGES_LOOKED_UP_ONE_BY_ONE = 100

/**
 * Page size when listing a folder for last actions. Each entry is only an id and, at most, one property.
 */
private const val LIST_PAGE_SIZE = 500

/**
 * Pages allowed beyond what the messages being looked up need, for pages Graph returns short.
 */
private const val EXTRA_LIST_PAGES = 2

/**
 * Reads what was last done with messages - replied to or forwarded - which delta sync cannot return.
 *
 * Exchange records it in the MAPI property PidTagLastVerbExecuted, which Graph returns only when asked to expand it,
 * and Microsoft 365 refuses a delta request that does. So it is read separately, after the round: message by message
 * for the few an incremental round reports, and for a whole initial round by listing the folder back to the oldest of
 * them, asking for nothing but ids and the property. Filtering the folder on the property instead would return far
 * less, but on a large folder Microsoft 365 took longer over it than the whole sync is allowed.
 *
 * Only the arrows depend on this, so a failure here is logged and the messages are returned as they were rather than
 * failing the sync.
 */
internal class GraphLastActionReader(
    private val client: GraphApiClient,
    private val logger: Logger,
) {
    fun withLastActions(folderServerId: String, messages: List<GraphMessage>): List<GraphMessage> {
        if (messages.isEmpty()) return messages

        val lastActions = readLastActions(folderServerId, messages)

        return messages.map { message ->
            lastActions[message.id]?.let { message.copy(singleValueExtendedProperties = it) } ?: message
        }
    }

    /**
     * Reads the last actions of messages already stored, from a listing of the folder back to [since].
     *
     * @return the property of each message found, by message id, or `null` when it could not be read.
     */
    @Suppress("TooGenericExceptionCaught")
    fun lastActionsOfStored(
        folderServerId: String,
        messageIds: Set<String>,
        since: Date?,
    ): Map<String, List<GraphExtendedProperty>>? {
        return try {
            listFolder(folderServerId, messageIds, since)
        } catch (e: Exception) {
            logger.warn(throwable = e) { "Could not read which stored messages were replied to or forwarded" }
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readLastActions(
        folderServerId: String,
        messages: List<GraphMessage>,
    ): Map<String, List<GraphExtendedProperty>> {
        return try {
            val lastActions = if (messages.size <= MAX_MESSAGES_LOOKED_UP_ONE_BY_ONE) {
                lookUpEach(messages)
            } else {
                listFolder(
                    folderServerId = folderServerId,
                    wanted = messages.mapTo(HashSet()) { it.id },
                    oldest = messages.mapNotNull { it.receivedDate() }.minOrNull(),
                )
            }
            logger.debug {
                val found = lastActions.values.count { it.isNotEmpty() }
                "Read last actions of ${messages.size} message(s); $found replied to or forwarded"
            }

            lastActions
        } catch (e: Exception) {
            logger.warn(throwable = e) { "Could not read which messages were replied to or forwarded" }
            emptyMap()
        }
    }

    private fun lookUpEach(messages: List<GraphMessage>): Map<String, List<GraphExtendedProperty>> {
        // A URL inside a batch is not encoded for us, and the expansion has spaces in it.
        val expand = MESSAGE_ENVELOPE_EXPAND.replace(" ", "%20")
        val urls = messages.map { message ->
            "/me/messages/${pathSegment(message.id)}?\$select=id&\$expand=$expand"
        }

        return client.batchGet(urls).values
            .map { body -> client.json.decodeFromJsonElement<GraphMessage>(body) }
            .associate { it.id to it.singleValueExtendedProperties }
    }

    private fun listFolder(
        folderServerId: String,
        wanted: Set<String>,
        oldest: Date?,
    ): Map<String, List<GraphExtendedProperty>> {
        var url: HttpUrl? = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
            addQueryParameter("\$select", "id")
            addQueryParameter("\$expand", MESSAGE_ENVELOPE_EXPAND)
            if (oldest != null) addQueryParameter("\$filter", "receivedDateTime ge ${oldest.toInstant()}")
            addQueryParameter("\$orderby", "receivedDateTime desc")
            addQueryParameter("\$top", LIST_PAGE_SIZE.toString())
        }

        val maxPages = wanted.size / LIST_PAGE_SIZE + EXTRA_LIST_PAGES
        return buildMap {
            var page = 0
            var seen = 0
            while (url != null && seen < wanted.size && page < maxPages) {
                val collection = client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))
                for (message in collection.value) {
                    if (message.id in wanted) {
                        put(message.id, message.singleValueExtendedProperties)
                        seen++
                    }
                }

                url = collection.nextLink?.let(client::absoluteUrl)
                page++
            }
        }
    }
}
