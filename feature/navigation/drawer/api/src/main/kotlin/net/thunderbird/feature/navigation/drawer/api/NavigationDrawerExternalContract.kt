package net.thunderbird.feature.navigation.drawer.api

import kotlinx.coroutines.flow.Flow

interface NavigationDrawerExternalContract {

    data class DrawerConfig(
        val showUnifiedFolders: Boolean,
        val showStarredCount: Boolean,
        val expandAllFolder: Boolean,
    )

    fun interface DrawerConfigLoader {
        fun loadDrawerConfigFlow(): Flow<DrawerConfig>
    }

    fun interface DrawerConfigWriter {
        fun writeDrawerConfig(drawerConfig: DrawerConfig)
    }

    /**
     * A folder of one account, listed beside the unified folders so it is one tap away whichever account the
     * drawer is showing.
     */
    data class PinnedFolder(
        val accountUuid: String,
        val folderId: Long,
    )

    /**
     * The folders pinned to the unified list, in the order they were pinned.
     *
     * Separate from the folders an account pins for filing mail into: those are move targets offered in a
     * message's toolbar, these are places to go.
     */
    interface PinnedFolderRepository {
        fun getPinnedFoldersFlow(): Flow<List<PinnedFolder>>

        fun isPinned(folder: PinnedFolder): Boolean

        fun pin(folder: PinnedFolder)

        fun unpin(folder: PinnedFolder)
    }
}
