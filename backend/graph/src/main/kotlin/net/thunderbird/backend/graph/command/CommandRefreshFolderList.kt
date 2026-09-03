package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.FolderType
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphFolderLister
import net.thunderbird.backend.graph.api.pathName
import net.thunderbird.backend.graph.api.resolveWellKnownFolderTypes
import net.thunderbird.feature.mail.folder.api.FOLDER_DEFAULT_PATH_DELIMITER
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * Fetches the mail folder hierarchy from Microsoft Graph and mirrors it into [BackendStorage].
 */
internal class CommandRefreshFolderList(
    private val backendStorage: BackendStorage,
    private val client: GraphApiClient,
) {
    private val folderLister = GraphFolderLister(client)

    fun refreshFolderList(): FolderPathDelimiter {
        val foldersOnServer = folderLister.listFolders()
        val wellKnownTypes = client.resolveWellKnownFolderTypes()
        val foldersById = foldersOnServer.associateBy { it.id }

        val folderInfos = foldersOnServer.map { folder ->
            FolderInfo(
                serverId = folder.id,
                name = folder.pathName(foldersById),
                type = wellKnownTypes[folder.id] ?: FolderType.REGULAR,
                folderPathDelimiter = FOLDER_DEFAULT_PATH_DELIMITER,
            )
        }

        val oldFolderServerIds = backendStorage.getFolderServerIds()
        val (toUpdate, toCreate) = folderInfos.partition { it.serverId in oldFolderServerIds }

        backendStorage.updateFolders {
            for (folder in toUpdate) {
                changeFolder(folderServerId = folder.serverId, name = folder.name, type = folder.type)
            }

            createFolders(toCreate)

            val currentFolderServerIds = folderInfos.mapTo(mutableSetOf()) { it.serverId }
            deleteFolders(oldFolderServerIds - currentFolderServerIds)
        }

        return FOLDER_DEFAULT_PATH_DELIMITER
    }
}
