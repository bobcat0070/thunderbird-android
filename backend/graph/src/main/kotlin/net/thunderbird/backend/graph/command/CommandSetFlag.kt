package net.thunderbird.backend.graph.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.thunderbird.backend.graph.api.FLAG_STATUS_FLAGGED
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.batchExecute
import net.thunderbird.backend.graph.api.graphBatchItem
import net.thunderbird.core.common.mail.Flag

private const val FLAG_STATUS_NOT_FLAGGED = "notFlagged"
private const val UNREAD_PAGE_SIZE = 100
private const val MAX_UNREAD_PAGES = 50

/**
 * Applies flag changes to messages on the server.
 *
 * Graph models only a subset of the IMAP flags: read state and the follow-up flag. Other flags are tracked locally
 * only, so requests to change them are ignored rather than failing the operation.
 *
 * Changes are sent in batches, because marking a whole folder read would otherwise be one request per message and
 * run into Graph throttling.
 */
internal class CommandSetFlag(
    private val client: GraphApiClient,
) {
    fun setFlag(messageServerIds: List<String>, flag: Flag, newState: Boolean) {
        val patch = flag.toPatch(newState) ?: return

        client.patchMessages(messageServerIds, patch)
    }

    /**
     * Marks every unread message in a folder as read.
     *
     * Graph has no bulk operation for this, so the unread messages are listed and patched in batches.
     */
    fun markAllAsRead(folderServerId: String) {
        val patch = buildJsonObject { put("isRead", true) }

        client.patchMessages(fetchUnreadMessageIds(folderServerId), patch)
    }

    private fun fetchUnreadMessageIds(folderServerId: String): List<String> {
        var url = client.url("me/mailFolders/$folderServerId/messages") {
            addQueryParameter("\$select", "id")
            addQueryParameter("\$filter", "isRead eq false")
            addQueryParameter("\$top", UNREAD_PAGE_SIZE.toString())
        }

        val messageServerIds = mutableListOf<String>()
        var page = 0

        while (page < MAX_UNREAD_PAGES) {
            val collection = client.json.decodeFromString<GraphCollection<GraphMessage>>(client.getString(url))
            messageServerIds += collection.value.map { it.id }

            val nextLink = collection.nextLink ?: break
            url = client.absoluteUrl(nextLink)
            page++
        }

        return messageServerIds
    }

    /**
     * @return the Graph patch body for [flag], or `null` when Graph has no equivalent property.
     */
    private fun Flag.toPatch(newState: Boolean): JsonObject? {
        return when (this) {
            Flag.SEEN -> buildJsonObject { put("isRead", newState) }

            Flag.FLAGGED -> buildJsonObject {
                put(
                    "flag",
                    buildJsonObject {
                        put("flagStatus", if (newState) FLAG_STATUS_FLAGGED else FLAG_STATUS_NOT_FLAGGED)
                    },
                )
            }

            else -> null
        }
    }
}

/**
 * Applies the same patch to every given message in as few requests as possible.
 */
private fun GraphApiClient.patchMessages(messageServerIds: List<String>, patch: JsonObject) {
    batchExecute(
        messageServerIds.mapIndexed { index, messageServerId ->
            graphBatchItem(index, "PATCH", "/me/messages/$messageServerId", patch)
        },
    )
}
