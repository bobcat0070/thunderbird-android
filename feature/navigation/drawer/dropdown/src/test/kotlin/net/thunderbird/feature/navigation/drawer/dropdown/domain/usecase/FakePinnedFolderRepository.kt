package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolderRepository

internal class FakePinnedFolderRepository(
    pinned: List<PinnedFolder> = emptyList(),
) : PinnedFolderRepository {
    val pinnedFolders = MutableStateFlow(pinned)

    override fun getPinnedFoldersFlow(): Flow<List<PinnedFolder>> = pinnedFolders

    override fun isPinned(folder: PinnedFolder): Boolean = folder in pinnedFolders.value

    override fun pin(folder: PinnedFolder) {
        pinnedFolders.update { it + folder }
    }

    override fun unpin(folder: PinnedFolder) {
        pinnedFolders.update { it - folder }
    }
}
