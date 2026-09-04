package net.thunderbird.backend.graph.command

import java.time.Instant
import java.util.Date
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.receivedDate
import okhttp3.HttpUrl

/**
 * Properties requested for the message envelopes shown in the message list.
 */
internal const val MESSAGE_ENVELOPE_SELECT =
    "id,isRead,isDraft,receivedDateTime,sentDateTime,internetMessageId,subject,from,sender,replyTo," +
        "toRecipients,ccRecipients,bccRecipients,hasAttachments,flag,bodyPreview,internetMessageHeaders"

private const val DELTA_PAGE_SIZE = 100

/**
 * Page size for a delta round.
 *
 * A delta query treats `$top` as a hard cap on the whole result set rather than a page size, which would silently
 * truncate the round and leave older mail unreachable. The page size is set with this header instead.
 */
private val DELTA_PAGE_HEADERS = mapOf("Prefer" to "odata.maxpagesize=$DELTA_PAGE_SIZE")

/**
 * Upper bound on pages fetched in one round.
 *
 * An incremental round is normally a single page. The bound matters for the initial round, which enumerates the
 * folder: without it, a very large mailbox could issue thousands of requests in one sync.
 */
private const val MAX_DELTA_PAGES = 50

/**
 * The outcome of one delta round.
 *
 * @param messages messages that were created or changed, newest first for an initial round.
 * @param removedMessageServerIds messages that left the folder, by being deleted or moved away.
 * @param deltaLink the token to resume from next time, or `null` if the round did not run to completion, in which
 *   case the next sync starts over rather than resuming from an incomplete state.
 */
internal data class GraphDeltaRound(
    val messages: List<GraphMessage>,
    val removedMessageServerIds: List<String>,
    val deltaLink: String?,
)

/**
 * Reads message changes from the Microsoft Graph delta endpoint.
 *
 * The first round for a folder enumerates it and ends with a delta token. Later rounds present that token and receive
 * only what changed since, which is what keeps frequent synchronization cheap: an idle folder costs a single request
 * returning an empty collection.
 *
 * See https://learn.microsoft.com/en-us/graph/delta-query-messages
 */
internal class GraphDeltaReader(
    private val client: GraphApiClient,
) {
    /**
     * Starts a new delta stream for a folder, enumerating the messages inside the sync window.
     *
     * A delta round runs to completion before it yields a resume token, so on a large folder it would otherwise walk
     * the whole thing just to keep the newest handful. The window is therefore bounded by date first: a single cheap
     * request finds when the [visibleLimit]-th newest message arrived, and the round starts from there.
     *
     * Delta accepts only `receivedDateTime` comparisons as a filter and only a descending `receivedDateTime` sort, so
     * the window is expressed with those and the newest messages arrive first.
     */
    fun initialRound(folderServerId: String, earliestPollDate: Date?, visibleLimit: Int): GraphDeltaRound {
        // The later of the two bounds satisfies both the configured sync window and the visible message count.
        val windowStart = listOfNotNull(earliestPollDate, findWindowStart(folderServerId, visibleLimit)).maxOrNull()

        val url = client.url("me/mailFolders/$folderServerId/messages/delta") {
            addQueryParameter("\$select", MESSAGE_ENVELOPE_SELECT)
            addQueryParameter("\$orderby", "receivedDateTime desc")

            if (windowStart != null) {
                addQueryParameter("\$filter", "receivedDateTime ge ${Instant.ofEpochMilli(windowStart.time)}")
            }
        }

        return readRound(url)
    }

    /**
     * Finds when the [visibleLimit]-th newest message in a folder arrived.
     *
     * @return that timestamp, or `null` when the folder holds no more than [visibleLimit] messages and so needs no
     *   date bound at all.
     */
    private fun findWindowStart(folderServerId: String, visibleLimit: Int): Date? {
        val url = client.url("me/mailFolders/$folderServerId/messages") {
            addQueryParameter("\$select", "receivedDateTime")
            addQueryParameter("\$orderby", "receivedDateTime desc")
            addQueryParameter("\$top", visibleLimit.toString())
        }

        val messages = client.json
            .decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))
            .value

        // Fewer than a full page means the whole folder fits inside the window.
        if (messages.size < visibleLimit) return null

        return messages.lastOrNull()?.receivedDate()
    }

    /**
     * Resumes an existing delta stream.
     */
    fun incrementalRound(deltaLink: String): GraphDeltaRound = readRound(client.absoluteUrl(deltaLink))

    /**
     * Follows `@odata.nextLink` until Graph returns the `@odata.deltaLink` that closes the round.
     */
    private fun readRound(startUrl: HttpUrl): GraphDeltaRound {
        val messages = mutableListOf<GraphMessage>()
        val removedMessageServerIds = mutableListOf<String>()

        var url = startUrl
        var deltaLink: String? = null
        var page = 0

        while (page < MAX_DELTA_PAGES) {
            val body = client.getString(url, DELTA_PAGE_HEADERS)
            val collection = client.json.decodeFromString<GraphCollection<GraphMessage>>(body)

            for (message in collection.value) {
                if (message.removed != null) {
                    removedMessageServerIds += message.id
                } else {
                    messages += message
                }
            }

            deltaLink = collection.deltaLink
            val nextLink = collection.nextLink
            if (deltaLink != null || nextLink == null) break

            url = client.absoluteUrl(nextLink)
            page++
        }

        return GraphDeltaRound(
            messages = messages,
            removedMessageServerIds = removedMessageServerIds,
            deltaLink = deltaLink,
        )
    }
}
