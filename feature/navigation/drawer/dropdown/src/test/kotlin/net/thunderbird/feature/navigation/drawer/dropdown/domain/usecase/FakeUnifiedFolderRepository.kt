package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract.UnifiedFolderRepository
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolderType

/**
 * Answers for the unified inbox from [displayUnifiedFolderFlow], and with an empty folder for every other type.
 */
internal class FakeUnifiedFolderRepository(
    private val displayUnifiedFolderFlow: Flow<UnifiedDisplayFolder>,
) : UnifiedFolderRepository {
    override fun getUnifiedDisplayFolderFlow(unifiedFolderType: UnifiedDisplayFolderType): Flow<UnifiedDisplayFolder> {
        return if (unifiedFolderType == UnifiedDisplayFolderType.INBOX) {
            displayUnifiedFolderFlow
        } else {
            flowOf(emptyUnifiedFolder(unifiedFolderType))
        }
    }

    companion object {
        fun emptyUnifiedFolder(type: UnifiedDisplayFolderType) = UnifiedDisplayFolder(
            id = type.id,
            unifiedType = type,
            unreadMessageCount = 0,
            starredMessageCount = 0,
        )

        /**
         * The unified folders other than the inbox, as this fake reports them.
         */
        val OTHER_UNIFIED_FOLDERS: List<UnifiedDisplayFolder> =
            UnifiedDisplayFolderType.entries.filter { it != UnifiedDisplayFolderType.INBOX }.map(::emptyUnifiedFolder)
    }
}
