package net.thunderbird.backend.graph.command

import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.batchExecute
import net.thunderbird.backend.graph.api.graphBatchItem

private const val DELETE_PAGE_SIZE = 100
private const val MAX_DELETE_PASSES = 100

/**
 * Deletes messages.
 *
 * A `DELETE` on a message moves it to the Deleted Items folder rather than erasing it, which matches how the app
 * treats a delete for accounts that have a trash folder.
 */
internal class CommandDelete(
    private val client: GraphApiClient,
) {
    fun deleteMessages(messageServerIds: List<String>) {
        client.batchExecute(
            messageServerIds.mapIndexed { index, messageServerId ->
                graphBatchItem(index, "DELETE", "/me/messages/$messageServerId")
            },
        )
    }

    /**
     * Deletes every message in a folder, one page at a time.
     *
     * The listing is repeated rather than paged through, because deleting messages shifts the remaining pages.
     */
    fun deleteAllMessages(folderServerId: String) {
        var pass = 0

        while (pass < MAX_DELETE_PASSES) {
            val messageServerIds = fetchMessageIdPage(folderServerId)
            if (messageServerIds.isEmpty()) return

            deleteMessages(messageServerIds)
            pass++
        }
    }

    private fun fetchMessageIdPage(folderServerId: String): List<String> {
        val url = client.url("me/mailFolders/$folderServerId/messages") {
            addQueryParameter("\$select", "id")
            addQueryParameter("\$top", DELETE_PAGE_SIZE.toString())
        }

        val collection = client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))

        return collection.value.map { it.id }
    }
}
