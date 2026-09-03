package net.thunderbird.backend.graph.command

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.batchExecute
import net.thunderbird.backend.graph.api.graphBatchItem

/**
 * Moves and copies messages between folders.
 *
 * Graph assigns a new id to a message when it changes folder, so both operations return a mapping from the original
 * message id to the id in the destination folder. A message whose request failed is left out of the mapping, so the
 * caller keeps referring to it by its original id.
 */
internal class CommandMoveOrCopy(
    private val client: GraphApiClient,
) {
    fun moveMessages(
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = relocate("move", targetFolderServerId, messageServerIds)

    fun copyMessages(
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = relocate("copy", targetFolderServerId, messageServerIds)

    /**
     * Marks messages as read and then moves them, matching the combined operation the app expects.
     */
    fun moveMessagesAndMarkAsRead(
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        val markAsRead = buildJsonObject { put("isRead", true) }

        client.batchExecute(
            messageServerIds.mapIndexed { index, messageServerId ->
                graphBatchItem(index, "PATCH", "/me/messages/$messageServerId", markAsRead)
            },
        )

        return moveMessages(targetFolderServerId, messageServerIds)
    }

    private fun relocate(
        action: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        val body = buildJsonObject { put("destinationId", targetFolderServerId) }

        val responses = client.batchExecute(
            messageServerIds.mapIndexed { index, messageServerId ->
                graphBatchItem(index, "POST", "/me/messages/$messageServerId/$action", body)
            },
        )

        return responses.mapNotNull { (index, response) ->
            val originalId = messageServerIds.getOrNull(index)
            val newId = response.body?.get("id")?.jsonPrimitive?.content

            if (response.isSuccess && originalId != null && newId != null) originalId to newId else null
        }.toMap()
    }
}
