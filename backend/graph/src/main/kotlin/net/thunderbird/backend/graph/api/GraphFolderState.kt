package net.thunderbird.backend.graph.api

import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts of a folder's state that reveal whether its contents changed.
 *
 * Graph exposes no change token on a mail folder, so the message counts stand in for one. They miss a change that
 * leaves both counts untouched, such as an edit in place, but they catch arriving, deleted and read messages, which
 * is what a push signal needs. The synchronization that follows reads the delta stream and is authoritative.
 */
internal data class GraphFolderState(
    val totalItemCount: Int,
    val unreadItemCount: Int,
)

/**
 * Reads the state of several folders in a single request.
 *
 * A poll runs as often as every fifteen seconds, so it is kept to one batched request regardless of how many folders
 * are pushed.
 */
internal fun GraphApiClient.readFolderStates(folderServerIds: List<String>): Map<String, GraphFolderState> {
    val bodies = batchGet(
        folderServerIds.map { "/me/mailFolders/$it?\$select=totalItemCount,unreadItemCount" },
    )

    return bodies.mapNotNull { (index, body) ->
        val folderServerId = folderServerIds.getOrNull(index)
        val totalItemCount = body["totalItemCount"]?.jsonPrimitive?.content?.toIntOrNull()
        val unreadItemCount = body["unreadItemCount"]?.jsonPrimitive?.content?.toIntOrNull()

        if (folderServerId != null && totalItemCount != null && unreadItemCount != null) {
            folderServerId to GraphFolderState(totalItemCount, unreadItemCount)
        } else {
            null
        }
    }.toMap()
}
