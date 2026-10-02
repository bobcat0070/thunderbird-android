package com.fsck.k9.preferences

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.Preferences
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.RemoteFolder
import net.thunderbird.feature.mail.folder.api.RemoteFolderDetails
import net.thunderbird.feature.mail.folder.api.data.repository.RemoteFolderDetailsRepository
import org.junit.Before
import org.junit.Test
import org.koin.core.component.inject

class CategoryGroupingStoreTest : K9RobolectricTest() {
    private val preferences: Preferences by inject()
    private val testSubject by lazy { PreferencesCategoryGroupingStore(preferences) }

    @Before
    fun before() {
        preferences.createStorageEditor().remove(VIEW_CATEGORY_GROUPING_KEY).commit()
    }

    @Test
    fun `a folder's choice should be kept against its server id`() {
        testSubject.setFolderGrouping("account", "Sent Items", CategoryGrouping.GROUPED)

        assertThat(testSubject.getFolderGrouping("account", "Sent Items")).isEqualTo(CategoryGrouping.GROUPED)
        // The same key an import writes a folder setting to, so an imported choice is read without further work.
        assertThat(preferences.storage.getStringOrNull("account.Sent Items.$FOLDER_CATEGORY_GROUPING_KEY"))
            .isEqualTo("GROUPED")
    }

    @Test
    fun `going back to the default should remove the folder's entry`() {
        testSubject.setFolderGrouping("account", "INBOX", CategoryGrouping.UNGROUPED)

        testSubject.setFolderGrouping("account", "INBOX", CategoryGrouping.DEFAULT)

        assertThat(preferences.storage.getStringOrNull("account.INBOX.$FOLDER_CATEGORY_GROUPING_KEY")).isNull()
    }

    @Test
    fun `choosing for one view should keep what was chosen for the others`() {
        testSubject.setViewGrouping("unified_folders", CategoryGrouping.UNGROUPED)
        testSubject.setViewGrouping("unified_sent", CategoryGrouping.GROUPED)

        assertThat(testSubject.getViewGrouping("unified_folders")).isEqualTo(CategoryGrouping.UNGROUPED)
        assertThat(testSubject.getViewGrouping("unified_sent")).isEqualTo(CategoryGrouping.GROUPED)
    }

    @Test
    fun `an entry that cannot be read should not cost the other views their choice`() {
        preferences.createStorageEditor()
            .putString(VIEW_CATEGORY_GROUPING_KEY, "unified_sent=GROUPED,garbage,new_messages=SIDEWAYS")
            .commit()

        assertThat(testSubject.getViewGrouping("unified_sent")).isEqualTo(CategoryGrouping.GROUPED)
        assertThat(testSubject.getViewGrouping("new_messages")).isEqualTo(CategoryGrouping.DEFAULT)
    }

    @Test
    fun `a folder's choice should be exported with its settings`() = runTest {
        val account = preferences.newAccount()
        testSubject.setFolderGrouping(account.uuid, "Sent", CategoryGrouping.GROUPED)
        val provider = FolderSettingsProvider(
            remoteFolderDetailsRepository = FakeRemoteFolderDetailsRepository(folder(1L, "Sent"), folder(2L, "Work")),
            categoryGroupingStore = testSubject,
        )

        val settings = provider.getFolderSettings(account)

        // A folder with nothing but defaults is left out of an export, so only the one with a choice appears.
        assertThat(settings.map { it.serverId to it.categoryGrouping })
            .isEqualTo(listOf("Sent" to CategoryGrouping.GROUPED))
    }

    private fun folder(id: Long, serverId: String) = RemoteFolderDetails(
        folder = RemoteFolder(id = id, serverId = serverId, name = serverId, type = FolderType.REGULAR),
        isInTopGroup = false,
        isIntegrate = false,
        isSyncEnabled = false,
        isVisible = true,
        isNotificationsEnabled = false,
        isPushEnabled = false,
    )
}

private class FakeRemoteFolderDetailsRepository(
    private vararg val folders: RemoteFolderDetails,
) : RemoteFolderDetailsRepository {
    override suspend fun getAllByAccountId(accountId: net.thunderbird.feature.account.AccountId) =
        Outcome.success(folders.toList())
}
