package net.thunderbird.feature.navigation.drawer.dropdown.data

import app.k9mail.legacy.message.controller.MessageCountsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolderType
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.createUnifiedFolderSearch

internal class UnifiedFolderRepository(
    private val messageCountsProvider: MessageCountsProvider,
) : DomainContract.UnifiedFolderRepository {

    override fun getUnifiedDisplayFolderFlow(unifiedFolderType: UnifiedDisplayFolderType): Flow<UnifiedDisplayFolder> {
        return messageCountsProvider.getMessageCountsFlow(createUnifiedFolderSearch(unifiedFolderType)).map {
            UnifiedDisplayFolder(
                id = unifiedFolderType.id,
                unifiedType = unifiedFolderType,
                unreadMessageCount = it.unread,
                starredMessageCount = it.starred,
            )
        }
    }

    private fun createUnifiedFolderSearch(unifiedFolderType: UnifiedDisplayFolderType): LocalMessageSearch {
        return createUnifiedFolderSearch(unifiedFolderType.kind).apply {
            id = unifiedFolderType.id
        }
    }

    companion object {
        const val UNIFIED_INBOX_ID = "unified_inbox"
    }
}
