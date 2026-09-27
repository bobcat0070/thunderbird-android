package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.preferences.CategoryGrouping
import com.fsck.k9.preferences.CategoryGroupingStore
import kotlin.test.Test
import net.thunderbird.feature.mail.folder.api.FolderType
import net.thunderbird.feature.search.legacy.SearchAccount
import net.thunderbird.feature.search.legacy.UnifiedFolderKind

class CategoryGroupingPreferenceTest {
    private val store = FakeCategoryGroupingStore()
    private var groupedByDefault = true
    private var savedDefault: Boolean? = null

    private val testSubject = CategoryGroupingPreference(
        store = store,
        isGroupedByDefault = { groupedByDefault },
        setGroupedByDefault = { savedDefault = it },
    )

    @Test
    fun `sent folder should start ungrouped`() {
        assertThat(testSubject.isGrouped(folder(FolderType.SENT))).isFalse()
    }

    @Test
    fun `drafts and outbox should start ungrouped`() {
        assertThat(testSubject.isGrouped(folder(FolderType.DRAFTS))).isFalse()
        assertThat(testSubject.isGrouped(folder(FolderType.OUTBOX))).isFalse()
    }

    @Test
    fun `other folders should follow the app-wide setting until chosen`() {
        groupedByDefault = false

        assertThat(testSubject.isGrouped(folder(FolderType.INBOX))).isFalse()
        assertThat(testSubject.isGrouped(folder(FolderType.REGULAR))).isFalse()
    }

    @Test
    fun `a folder's own choice should win over its default`() {
        testSubject.setGrouped(folder(FolderType.SENT), isGrouped = true)

        assertThat(testSubject.isGrouped(folder(FolderType.SENT))).isTrue()
    }

    @Test
    fun `choosing for one folder should leave other folders and the app-wide setting alone`() {
        testSubject.setGrouped(folder(FolderType.INBOX, serverId = "INBOX"), isGrouped = false)

        assertThat(testSubject.isGrouped(folder(FolderType.REGULAR, serverId = "Work"))).isTrue()
        assertThat(savedDefault).isNull()
    }

    @Test
    fun `unified sent should start ungrouped and unified inbox follow the app-wide setting`() {
        assertThat(testSubject.isGrouped(CategoryGroupingScope.View(UnifiedFolderKind.SENT.searchId))).isFalse()
        assertThat(testSubject.isGrouped(CategoryGroupingScope.View(SearchAccount.UNIFIED_FOLDERS))).isTrue()
    }

    @Test
    fun `a view's own choice should be kept for that view`() {
        val view = CategoryGroupingScope.View(SearchAccount.UNIFIED_FOLDERS)

        testSubject.setGrouped(view, isGrouped = false)

        assertThat(testSubject.isGrouped(view)).isFalse()
        assertThat(store.viewGroupings[SearchAccount.UNIFIED_FOLDERS]).isEqualTo(CategoryGrouping.UNGROUPED)
    }

    @Test
    fun `a list with nothing to remember a choice by should change the app-wide setting`() {
        testSubject.setGrouped(CategoryGroupingScope.Elsewhere, isGrouped = false)

        assertThat(savedDefault).isEqualTo(false)
    }

    private fun folder(type: FolderType, serverId: String = type.name) =
        CategoryGroupingScope.Folder(accountUuid = "account", serverId = serverId, type = type)
}

private class FakeCategoryGroupingStore : CategoryGroupingStore {
    val folderGroupings = mutableMapOf<Pair<String, String>, CategoryGrouping>()
    val viewGroupings = mutableMapOf<String, CategoryGrouping>()

    override fun getFolderGrouping(accountUuid: String, folderServerId: String): CategoryGrouping =
        folderGroupings[accountUuid to folderServerId] ?: CategoryGrouping.DEFAULT

    override fun setFolderGrouping(accountUuid: String, folderServerId: String, grouping: CategoryGrouping) {
        folderGroupings[accountUuid to folderServerId] = grouping
    }

    override fun getViewGrouping(viewId: String): CategoryGrouping = viewGroupings[viewId] ?: CategoryGrouping.DEFAULT

    override fun setViewGrouping(viewId: String, grouping: CategoryGrouping) {
        viewGroupings[viewId] = grouping
    }
}
