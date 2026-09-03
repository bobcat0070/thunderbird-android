package net.thunderbird.backend.graph.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.thunderbird.feature.mail.folder.api.FOLDER_DEFAULT_PATH_DELIMITER

private const val FOLDER_SELECT = "id,displayName,parentFolderId,childFolderCount"
private const val FOLDER_PAGE_SIZE = 100
private const val MAX_FOLDER_PAGES = 50

/**
 * Maximum folder nesting level that is traversed.
 *
 * Each level costs one batched round trip, so this also bounds the number of requests listing folders can make.
 */
private const val MAX_FOLDER_DEPTH = 10

/**
 * Reads the mail folder hierarchy from Microsoft Graph.
 *
 * Graph only returns the folders directly below the mailbox root from `/me/mailFolders`; nested folders have to be
 * requested per parent via `childFolders`. The hierarchy is therefore walked breadth-first, with each level requested
 * in a single batched call, so the number of round trips grows with folder depth rather than folder count.
 */
internal class GraphFolderLister(
    private val client: GraphApiClient,
) {
    /**
     * Walks the folder tree breadth-first, batching the child lookups of each level.
     */
    fun listFolders(): List<GraphMailFolder> {
        val allFolders = mutableListOf<GraphMailFolder>()
        val seenIds = mutableSetOf<String>()

        var currentLevel = fetchTopLevelFolders()
        var depth = 0

        while (currentLevel.isNotEmpty() && depth < MAX_FOLDER_DEPTH) {
            val newFolders = currentLevel.filter { seenIds.add(it.id) }
            allFolders += newFolders

            currentLevel = fetchChildFolders(newFolders.filter { it.childFolderCount > 0 })
            depth++
        }

        return allFolders
    }

    private fun fetchTopLevelFolders(): List<GraphMailFolder> {
        var url = client.url("me/mailFolders") {
            addQueryParameter("\$select", FOLDER_SELECT)
            addQueryParameter("\$top", FOLDER_PAGE_SIZE.toString())
        }

        val folders = mutableListOf<GraphMailFolder>()
        var page = 0

        while (page < MAX_FOLDER_PAGES) {
            val collection = client.json.decodeFromString<GraphCollection<GraphMailFolder>>(client.getString(url))
            folders += collection.value

            val nextLink = collection.nextLink ?: break
            url = client.absoluteUrl(nextLink)
            page++
        }

        return folders
    }

    /**
     * Requests the child folders of every given parent in one batch.
     *
     * A parent with more children than [FOLDER_PAGE_SIZE] is truncated rather than paged, because batched requests do
     * not expose a follow-up link. This is not a practical limitation for mail folders.
     */
    private fun fetchChildFolders(parents: List<GraphMailFolder>): List<GraphMailFolder> {
        if (parents.isEmpty()) return emptyList()

        val bodies = client.batchGet(
            parents.map { parent ->
                "/me/mailFolders/${parent.id}/childFolders?\$select=$FOLDER_SELECT&\$top=$FOLDER_PAGE_SIZE"
            },
        )

        return bodies.values.flatMap { body -> body.toFolderList() }
    }

    private fun JsonObject.toFolderList(): List<GraphMailFolder> {
        return client.json.decodeFromJsonElement<GraphCollection<GraphMailFolder>>(this).value
    }
}

/**
 * Builds the full folder path by walking up the parent chain.
 *
 * The depth is bounded so a response with an unexpected parent cycle cannot hang the caller.
 */
internal fun GraphMailFolder.pathName(foldersById: Map<String, GraphMailFolder>): String {
    val segments = mutableListOf<String>()
    var current: GraphMailFolder? = this
    var depth = 0

    while (current != null && depth < MAX_FOLDER_DEPTH) {
        segments += current.displayName ?: current.id
        current = current.parentFolderId?.let { foldersById[it] }
        depth++
    }

    return segments.asReversed().joinToString(FOLDER_DEFAULT_PATH_DELIMITER)
}
