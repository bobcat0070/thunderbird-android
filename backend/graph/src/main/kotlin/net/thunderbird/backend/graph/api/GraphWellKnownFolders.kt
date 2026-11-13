package net.thunderbird.backend.graph.api

import com.fsck.k9.mail.FolderType
import kotlinx.serialization.json.jsonPrimitive
import net.thunderbird.core.common.exception.MessagingException

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
 * once and used to assign folder types. A folder Graph reports as not found does not exist for the account (Archive
 * is optional in some mailboxes) and is simply absent from the result.
 *
 * Any other failure fails the whole lookup, so the folder list refresh it belongs to is tried again later. Leaving the
 * folder out instead would store it as a regular folder: during a Microsoft outage that turned the Inbox into one, and
 * an account without an Inbox drops out of the Unified Inbox.
 *
 * @return a map of folder id to [FolderType].
 * @throws MessagingException if a folder could not be looked up, or the Inbox was not found.
 */
internal fun GraphApiClient.resolveWellKnownFolderTypes(): Map<String, FolderType> {
    val names = WELL_KNOWN_FOLDERS.keys.toList()
    val responses = batchExecute(
        names.mapIndexed { index, name -> graphBatchItem(index, "GET", "/me/mailFolders/$name?\$select=id") },
    )

    return names.mapIndexedNotNull { index, name ->
        val response = responses[index]
        val folderId = response?.body?.get("id")?.jsonPrimitive?.content

        when {
            response?.isSuccess == true && folderId != null -> folderId to WELL_KNOWN_FOLDERS.getValue(name)

            // Every mailbox has an Inbox, so not finding it is a server fault as well.
            response?.status == HTTP_NOT_FOUND && name != INBOX -> null

            else -> throw MessagingException(
                "Microsoft Graph could not look up the $name folder (HTTP ${response?.status ?: "no response"})",
                false,
            )
        }
    }.toMap()
}

private const val INBOX = "inbox"
