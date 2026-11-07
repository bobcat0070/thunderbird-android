package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.thunderbird.feature.mail.folder.FolderRole
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract.UnifiedFolderRepository
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolder

/**
 * Answers from [displayUnifiedFolderFlows], and with an empty folder for every role it has no flow for.
 */
internal class FakeUnifiedFolderRepository(
    private val displayUnifiedFolderFlows: Map<FolderRole, Flow<UnifiedDisplayFolder>> = emptyMap(),
) : UnifiedFolderRepository {
    override fun getUnifiedDisplayFolderFlow(role: FolderRole): Flow<UnifiedDisplayFolder> {
        return displayUnifiedFolderFlows[role] ?: flowOf(emptyUnifiedFolder(role))
    }

    companion object {
        fun emptyUnifiedFolder(role: FolderRole) = UnifiedDisplayFolder(
            role = role,
            unreadMessageCount = 0,
            starredMessageCount = 0,
        )
    }
}
