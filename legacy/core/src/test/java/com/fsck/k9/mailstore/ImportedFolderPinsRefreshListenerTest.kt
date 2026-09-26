package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageStore
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.Preferences
import com.fsck.k9.preferences.FolderPinSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.koin.core.component.inject
import org.mockito.kotlin.mock

private const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
private const val OTHER_ACCOUNT = "22222222-2222-4222-8222-222222222222"

class ImportedFolderPinsRefreshListenerTest : K9RobolectricTest() {
    private val preferences: Preferences by inject()
    private val pins = FakeFolderPinSettings()

    @Test
    fun `an imported pin should be applied to the folder with that server id`() = runTest {
        pending("$ACCOUNT.Projects.pinnedToDrawer" to "true", "$ACCOUNT.Projects.pinnedForFiling" to "true")

        listenerFor(folders = mapOf("Projects" to 7L)).onAfterFolderListRefresh()

        assertThat(pins.applied).containsExactlyInAnyOrder(Applied(ACCOUNT, 7, forFiling = true, toDrawer = true))
    }

    @Test
    fun `an applied pin should no longer be pending`() = runTest {
        pending("$ACCOUNT.Projects.pinnedToDrawer" to "true")

        listenerFor(folders = mapOf("Projects" to 7L)).onAfterFolderListRefresh()

        assertThat(preferences.storage.getStringOrNull("$ACCOUNT.Projects.pinnedToDrawer")).isNull()
    }

    @Test
    fun `a pin whose folder does not exist yet should wait for a later refresh`() = runTest {
        // The first sync can finish before the whole folder list has arrived.
        pending("$ACCOUNT.Later.pinnedToDrawer" to "true")

        listenerFor(folders = emptyMap()).onAfterFolderListRefresh()

        assertThat(pins.applied).isEmpty()
        assertThat(preferences.storage.getBoolean("$ACCOUNT.Later.pinnedToDrawer", false)).isTrue()
    }

    @Test
    fun `a server id containing dots should be read whole`() = runTest {
        // IMAP hierarchies are often dot-separated, so the setting name is taken off the end.
        pending("$ACCOUNT.INBOX.Receipts.2026.pinnedForFiling" to "true")

        listenerFor(folders = mapOf("INBOX.Receipts.2026" to 9L)).onAfterFolderListRefresh()

        assertThat(pins.applied).containsExactlyInAnyOrder(Applied(ACCOUNT, 9, forFiling = true, toDrawer = false))
    }

    @Test
    fun `another account's pins should be left for that account`() = runTest {
        pending("$OTHER_ACCOUNT.Projects.pinnedToDrawer" to "true")

        listenerFor(folders = mapOf("Projects" to 7L)).onAfterFolderListRefresh()

        assertThat(pins.applied).isEmpty()
        assertThat(preferences.storage.getBoolean("$OTHER_ACCOUNT.Projects.pinnedToDrawer", false)).isTrue()
    }

    @Test
    fun `a folder imported as not pinned should not be pinned`() = runTest {
        pending("$ACCOUNT.Projects.pinnedToDrawer" to "false", "$ACCOUNT.Projects.pinnedForFiling" to "false")

        listenerFor(folders = mapOf("Projects" to 7L)).onAfterFolderListRefresh()

        assertThat(pins.applied.single().forFiling).isFalse()
        assertThat(pins.applied.single().toDrawer).isFalse()
    }

    private fun pending(vararg entries: Pair<String, String>) {
        val editor = preferences.createStorageEditor()
        entries.forEach { (key, value) -> editor.putString(key, value) }
        editor.commit()
    }

    private fun listenerFor(folders: Map<String, Long>) = ImportedFolderPinsRefreshListener(
        preferences = preferences,
        accountUuid = ACCOUNT,
        messageStore = FakeMessageStore(folders),
        folderPinSettings = pins,
    )

    private data class Applied(val account: String, val folderId: Long, val forFiling: Boolean, val toDrawer: Boolean)

    private class FakeFolderPinSettings : FolderPinSettings {
        val applied = mutableListOf<Applied>()

        override fun isPinnedForFiling(accountUuid: String, folderId: Long) = false

        override fun isPinnedToDrawer(accountUuid: String, folderId: Long) = false

        override fun pin(accountUuid: String, folderId: Long, forFiling: Boolean, toDrawer: Boolean) {
            applied += Applied(accountUuid, folderId, forFiling, toDrawer)
        }
    }

    /**
     * Answers only the question the listener asks; everything else is left to a delegate that is never called.
     */
    private class FakeMessageStore(
        private val folderIds: Map<String, Long>,
        private val delegate: MessageStore = mock(),
    ) : MessageStore by delegate {
        override fun getFolderId(folderServerId: String): Long? = folderIds[folderServerId]
    }
}
