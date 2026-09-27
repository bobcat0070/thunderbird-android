package com.fsck.k9.ui.managefolders

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.preferences.CategoryGrouping
import com.fsck.k9.preferences.CategoryGroupingStore
import kotlin.test.Test
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.FolderType
import org.mockito.kotlin.mock

class FolderSettingsDataStoreTest {
    private val store = FakeCategoryGroupingStore()

    @Test
    fun `a folder with no choice should show the default`() {
        val testSubject = dataStoreFor(serverId = "Sent")

        assertThat(testSubject.getString(PREFERENCE_CATEGORY_GROUPING, null)).isEqualTo("DEFAULT")
    }

    @Test
    fun `a choice made here should be kept where the message list reads it`() {
        val testSubject = dataStoreFor(serverId = "Sent")

        testSubject.putString(PREFERENCE_CATEGORY_GROUPING, "GROUPED")

        assertThat(store.folderGroupings["account" to "Sent"]).isEqualTo(CategoryGrouping.GROUPED)
        assertThat(testSubject.getString(PREFERENCE_CATEGORY_GROUPING, null)).isEqualTo("GROUPED")
    }

    @Test
    fun `a choice made from the message list should show here`() {
        store.folderGroupings["account" to "INBOX"] = CategoryGrouping.UNGROUPED

        assertThat(dataStoreFor(serverId = "INBOX").getString(PREFERENCE_CATEGORY_GROUPING, null))
            .isEqualTo("UNGROUPED")
    }

    @Test
    fun `a folder only on the device should keep no choice`() {
        val testSubject = dataStoreFor(serverId = null)

        testSubject.putString(PREFERENCE_CATEGORY_GROUPING, "GROUPED")

        assertThat(store.folderGroupings.isEmpty()).isEqualTo(true)
    }

    private fun dataStoreFor(serverId: String?): FolderSettingsDataStore {
        val folder = FolderDetails(
            folder = Folder(id = 1, name = "Folder", type = FolderType.REGULAR, isLocalOnly = serverId == null),
            isInTopGroup = false,
            isIntegrate = false,
            isSyncEnabled = true,
            isVisible = true,
            isNotificationsEnabled = false,
            isPushEnabled = false,
        )

        return FolderSettingsDataStore(
            folderDetailsRepository = mock(),
            accountId = AccountIdFactory.create(),
            folder = folder,
            categoryGrouping = serverId?.let { FolderSettingsDataStore.FolderCategoryGrouping(store, "account", it) },
        )
    }
}

private class FakeCategoryGroupingStore : CategoryGroupingStore {
    val folderGroupings = mutableMapOf<Pair<String, String>, CategoryGrouping>()

    override fun getFolderGrouping(accountUuid: String, folderServerId: String): CategoryGrouping =
        folderGroupings[accountUuid to folderServerId] ?: CategoryGrouping.DEFAULT

    override fun setFolderGrouping(accountUuid: String, folderServerId: String, grouping: CategoryGrouping) {
        folderGroupings[accountUuid to folderServerId] = grouping
    }

    override fun getViewGrouping(viewId: String): CategoryGrouping = CategoryGrouping.DEFAULT

    override fun setViewGrouping(viewId: String, grouping: CategoryGrouping) = Unit
}
