package net.thunderbird.backend.graph.command

import java.util.Date
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.pathSegment
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
 * An incremental round is normally a single page. An initial round is allowed as many pages as its window needs, see
 * [GraphDeltaReader.initialRound], but never fewer than this.
 */
private const val MAX_DELTA_PAGES = 50

/**
 * Pages allowed beyond what the visible limit needs, for pages Graph returns short.
 */
private const val EXTRA_PAGES = 10

/**
 * Page size when listing the messages a filtered delta round leaves out. Graph accepts 1 to 1000; the full envelope
 * carries every header, so pages are kept as small as the delta round's to stay clear of gateway timeouts.
 */
private const val LIST_PAGE_SIZE = 100

/**
 * A visible limit meaning every message in the folder, which is what the "all messages" setting asks for.
 */
internal const val UNLIMITED_VISIBLE_LIMIT = Int.MAX_VALUE

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
     *
     * A filtered delta round returns at most 5000 messages, however many the filter matches. Whatever inside the
     * window it leaves out is listed from the folder instead, so a larger visible limit is still filled.
     */
    fun initialRound(folderServerId: String, earliestPollDate: Date?, visibleLimit: Int): GraphDeltaRound {
        // The later of the two bounds satisfies both the configured sync window and the visible message count.
        val windowStart = listOfNotNull(earliestPollDate, findWindowStart(folderServerId, visibleLimit)).maxOrNull()

        val url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages/delta") {
            addQueryParameter("\$select", MESSAGE_ENVELOPE_SELECT)
            addQueryParameter("\$orderby", "receivedDateTime desc")

            if (windowStart != null) {
                addQueryParameter("\$filter", "receivedDateTime ge ${windowStart.toInstant()}")
            }
        }

        val round = readRound(url, maxPages = pagesFor(visibleLimit))
        if (windowStart == null || round.messages.size >= visibleLimit) return round

        val olderMessages = listOlderMessages(folderServerId, windowStart, round.messages, visibleLimit)

        return round.copy(messages = round.messages + olderMessages)
    }

    /**
     * Finds when the [visibleLimit]-th newest message in a folder arrived.
     *
     * Asks for that one message by position rather than for the whole window: Graph pages at most 1000 messages at a
     * time, so a larger window could not be fetched in one request.
     *
     * @return that timestamp, or `null` when the folder holds no more than [visibleLimit] messages and so needs no
     *   date bound at all.
     */
    private fun findWindowStart(folderServerId: String, visibleLimit: Int): Date? {
        if (visibleLimit == UNLIMITED_VISIBLE_LIMIT) return null

        val url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
            addQueryParameter("\$select", "receivedDateTime")
            addQueryParameter("\$orderby", "receivedDateTime desc")
            addQueryParameter("\$top", "1")
            addQueryParameter("\$skip", (visibleLimit - 1).toString())
        }

        val messages = client.json
            .decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))
            .value

        // Nothing at that position means the whole folder fits inside the window.
        return messages.firstOrNull()?.receivedDate()
    }

    /**
     * Lists the messages inside the window that the delta round did not return, continuing back in time from where
     * the round stopped.
     *
     * The range runs up to and including the oldest message the round did return, so messages that arrived at the
     * same instant are not skipped; those the round already has are dropped.
     */
    private fun listOlderMessages(
        folderServerId: String,
        windowStart: Date,
        deltaMessages: List<GraphMessage>,
        visibleLimit: Int,
    ): List<GraphMessage> {
        val oldestDeltaMessage = deltaMessages.mapNotNull { it.receivedDate() }.minOrNull() ?: return emptyList()
        val knownIds = deltaMessages.mapTo(HashSet()) { it.id }
        val wanted = visibleLimit - deltaMessages.size

        var url: HttpUrl? = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
            addQueryParameter("\$select", MESSAGE_ENVELOPE_SELECT)
            addQueryParameter(
                "\$filter",
                "receivedDateTime ge ${windowStart.toInstant()} and " +
                    "receivedDateTime le ${oldestDeltaMessage.toInstant()}",
            )
            addQueryParameter("\$orderby", "receivedDateTime desc")
            addQueryParameter("\$top", LIST_PAGE_SIZE.toString())
        }

        val olderMessages = mutableListOf<GraphMessage>()
        val maxPages = wanted / LIST_PAGE_SIZE + EXTRA_PAGES
        var page = 0

        while (url != null && olderMessages.size < wanted && page < maxPages) {
            val collection = client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))

            for (message in collection.value) {
                if (knownIds.add(message.id)) olderMessages += message
            }

            url = collection.nextLink?.let(client::absoluteUrl)
            page++
        }

        return olderMessages.take(wanted)
    }

    /**
     * The page bound for an initial round: enough to enumerate the whole window, so the round reaches the token that
     * lets later syncs resume. A lower bound would leave a large window without one, and every sync would enumerate
     * the folder again.
     */
    private fun pagesFor(visibleLimit: Int): Int {
        if (visibleLimit == UNLIMITED_VISIBLE_LIMIT) return Int.MAX_VALUE

        return maxOf(MAX_DELTA_PAGES, visibleLimit / DELTA_PAGE_SIZE + EXTRA_PAGES)
    }

    /**
     * Resumes an existing delta stream.
     */
    fun incrementalRound(deltaLink: String): GraphDeltaRound =
        readRound(client.absoluteUrl(deltaLink), maxPages = MAX_DELTA_PAGES)

    /**
     * Follows `@odata.nextLink` until Graph returns the `@odata.deltaLink` that closes the round.
     */
    private fun readRound(startUrl: HttpUrl, maxPages: Int): GraphDeltaRound {
        val messages = mutableListOf<GraphMessage>()
        val removedMessageServerIds = mutableListOf<String>()

        var url = startUrl
        var deltaLink: String? = null
        var page = 0

        while (page < maxPages) {
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
