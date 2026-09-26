package net.thunderbird.app.common.feature.settings

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.preferences.ExternalSettingKeys.REMOTE_IMAGE_TRUSTED_DOMAINS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.REMOTE_IMAGE_TRUSTED_SENDERS_KEY
import com.fsck.k9.ui.messageview.RemoteImageScope
import com.fsck.k9.ui.messageview.RemoteImageSenderStore
import com.fsck.k9.ui.settings.account.PinnedFolderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolderRepository
import org.junit.Test
import org.robolectric.RuntimeEnvironment

private const val ACCOUNT = "11111111-1111-4111-8111-111111111111"

class RemoteImageSendersExternalSettingsTest : RobolectricTest() {
    private val store = RemoteImageSenderStore(RuntimeEnvironment.getApplication())
    private val testSubject = RemoteImageSendersExternalSettings(store)

    @Test
    fun `trusted senders and domains should survive a round trip`() {
        store.trust("sam@example.com", RemoteImageScope.SENDER)
        store.trust("news@shop.example", RemoteImageScope.DOMAIN)
        val exported = testSubject.exportSettings()
        store.forget("sam@example.com", RemoteImageScope.SENDER)
        store.forget("news@shop.example", RemoteImageScope.DOMAIN)

        testSubject.importSettings(exported)

        assertThat(store.trusted()).containsExactlyInAnyOrder(
            RemoteImageScope.SENDER to "sam@example.com",
            RemoteImageScope.DOMAIN to "shop.example",
        )
    }

    @Test
    fun `import should add to what is trusted here, never remove it`() {
        store.trust("kept@example.com", RemoteImageScope.SENDER)

        testSubject.importSettings(mapOf(REMOTE_IMAGE_TRUSTED_SENDERS_KEY to """["new@example.com"]"""))

        assertThat(store.trusted().map { it.second }).containsExactlyInAnyOrder("kept@example.com", "new@example.com")
    }

    @Test
    fun `values of the wrong shape should not be trusted`() {
        // A malformed file must not end up trusting something the user never chose.
        testSubject.importSettings(
            mapOf(
                REMOTE_IMAGE_TRUSTED_SENDERS_KEY to """["not-an-address", ""]""",
                REMOTE_IMAGE_TRUSTED_DOMAINS_KEY to """["someone@example.com"]""",
            ),
        )

        assertThat(store.trusted()).isEmpty()
    }

    @Test
    fun `an unreadable value should be skipped rather than fail the import`() {
        testSubject.importSettings(mapOf(REMOTE_IMAGE_TRUSTED_SENDERS_KEY to "[broken"))

        assertThat(store.trusted()).isEmpty()
    }
}

class DefaultFolderPinSettingsTest : RobolectricTest() {
    private val pinnedFolderStore = PinnedFolderStore(RuntimeEnvironment.getApplication())
    private val drawerPins = FakePinnedFolderRepository()
    private val testSubject = DefaultFolderPinSettings(pinnedFolderStore, drawerPins)

    @Test
    fun `pinning for filing should add to the folders already pinned`() {
        pinnedFolderStore.setPinnedFolderIds(ACCOUNT, setOf(3L))

        testSubject.pin(ACCOUNT, folderId = 7, forFiling = true, toDrawer = false)

        assertThat(pinnedFolderStore.pinnedFolderIds(ACCOUNT)).containsExactlyInAnyOrder(3L, 7L)
        assertThat(drawerPins.isPinned(PinnedFolder(ACCOUNT, 7))).isFalse()
    }

    @Test
    fun `pinning to the drawer should not also pin for filing`() {
        testSubject.pin(ACCOUNT, folderId = 7, forFiling = false, toDrawer = true)

        assertThat(drawerPins.isPinned(PinnedFolder(ACCOUNT, 7))).isTrue()
        assertThat(pinnedFolderStore.pinnedFolderIds(ACCOUNT)).isEmpty()
    }

    @Test
    fun `both kinds of pin should be reported for export`() {
        testSubject.pin(ACCOUNT, folderId = 7, forFiling = true, toDrawer = true)

        assertThat(testSubject.isPinnedForFiling(ACCOUNT, 7)).isTrue()
        assertThat(testSubject.isPinnedToDrawer(ACCOUNT, 7)).isTrue()
        assertThat(testSubject.isPinnedForFiling(ACCOUNT, 8)).isFalse()
    }

    private class FakePinnedFolderRepository : PinnedFolderRepository {
        private val pinned = MutableStateFlow(emptyList<PinnedFolder>())

        override fun getPinnedFoldersFlow(): Flow<List<PinnedFolder>> = pinned

        override fun isPinned(folder: PinnedFolder): Boolean = folder in pinned.value

        override fun pin(folder: PinnedFolder) {
            pinned.value = pinned.value + folder
        }

        override fun unpin(folder: PinnedFolder) {
            pinned.value = pinned.value - folder
        }
    }
}
