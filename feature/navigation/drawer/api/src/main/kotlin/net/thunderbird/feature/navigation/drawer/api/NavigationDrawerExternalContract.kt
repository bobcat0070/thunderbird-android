package net.thunderbird.feature.navigation.drawer.api

import android.content.Context
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
     * Screens of their own the drawer offers a way to, beside the folders.
     */
    enum class DrawerShortcut {
        PACKAGES,
        TRIPS,
    }

    /**
     * Which shortcuts the drawer shows, as the reader's settings change, and how they open.
     */
    interface DrawerShortcuts {
        fun getShortcutsFlow(): Flow<List<DrawerShortcut>>

        /**
         * Opens [shortcut]'s screen from [context], the activity showing the drawer, so that going back returns to
         * it.
         */
        fun open(context: Context, shortcut: DrawerShortcut)
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
