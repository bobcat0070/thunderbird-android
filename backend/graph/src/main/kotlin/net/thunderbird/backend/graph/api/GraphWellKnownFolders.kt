package net.thunderbird.backend.graph.api

import com.fsck.k9.mail.FolderType
import kotlinx.serialization.json.jsonPrimitive

/**
 * Graph well-known folder names mapped onto the folder types the app models.
 *
 * See https://learn.microsoft.com/en-us/graph/api/resources/mailfolder
 */
internal val WELL_KNOWN_FOLDERS: Map<String, FolderType> = linkedMapOf(
    "inbox" to FolderType.INBOX,
    "drafts" to FolderType.DRAFTS,
    "sentitems" to FolderType.SENT,
    "deleteditems" to FolderType.TRASH,
    "junkemail" to FolderType.SPAM,
    "archive" to FolderType.ARCHIVE,
    "outbox" to FolderType.OUTBOX,
)

/**
 * Resolves the folder ids of the well-known Graph folders.
 *
 * Graph addresses these folders by name, but the rest of the API identifies folders by id, so the names are resolved
 * once and used to assign folder types. Folders that do not exist for an account (Archive is optional in some
 * mailboxes) are simply absent from the result.
 *
 * @return a map of folder id to [FolderType].
 */
internal fun GraphApiClient.resolveWellKnownFolderTypes(): Map<String, FolderType> {
    val names = WELL_KNOWN_FOLDERS.keys.toList()
    val bodies = batchGet(names.map { "/me/mailFolders/$it?\$select=id" })

    return bodies.mapNotNull { (index, body) ->
        val folderId = body["id"]?.jsonPrimitive?.content
        val type = names.getOrNull(index)?.let { WELL_KNOWN_FOLDERS[it] }

        if (folderId != null && type != null) folderId to type else null
    }.toMap()
}
