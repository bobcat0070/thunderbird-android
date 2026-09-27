package net.thunderbird.backend.graph.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.thunderbird.backend.graph.api.FLAG_STATUS_FLAGGED
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.ICON_INDEX_FORWARDED
import net.thunderbird.backend.graph.api.ICON_INDEX_PROPERTY
import net.thunderbird.backend.graph.api.ICON_INDEX_REPLIED
import net.thunderbird.backend.graph.api.LAST_VERB_EXECUTED_PROPERTY
import net.thunderbird.backend.graph.api.LAST_VERB_FORWARD
import net.thunderbird.backend.graph.api.LAST_VERB_REPLY_TO_SENDER
import net.thunderbird.backend.graph.api.batchExecute
import net.thunderbird.backend.graph.api.graphBatchItem
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.backend.graph.api.requireSuccess
import net.thunderbird.core.common.mail.Flag

private const val FLAG_STATUS_NOT_FLAGGED = "notFlagged"
private const val UNREAD_PAGE_SIZE = 100
private const val MAX_UNREAD_PAGES = 50

/**
 * Applies flag changes to messages on the server.
 *
 * Graph models read state and the follow-up flag directly. Replied and forwarded are written the way Outlook records
 * them, as the message's last action and matching icon, so Outlook shows a reply sent from here. Other flags are
 * tracked locally only, so requests to change them are ignored rather than failing the operation.
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
        var url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
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

            // Exchange keeps only the last action, and has no way to say none was taken, so clearing is left local.
            Flag.ANSWERED -> if (newState) lastActionPatch(LAST_VERB_REPLY_TO_SENDER, ICON_INDEX_REPLIED) else null

            Flag.FORWARDED -> if (newState) lastActionPatch(LAST_VERB_FORWARD, ICON_INDEX_FORWARDED) else null

            else -> null
        }
    }

    private fun lastActionPatch(lastVerb: Int, iconIndex: Int): JsonObject = buildJsonObject {
        put(
            "singleValueExtendedProperties",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", LAST_VERB_EXECUTED_PROPERTY)
                        put("value", lastVerb.toString())
                    },
                    buildJsonObject {
                        put("id", ICON_INDEX_PROPERTY)
                        put("value", iconIndex.toString())
                    },
                ),
            ),
        )
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
    ).requireSuccess("update")
}
