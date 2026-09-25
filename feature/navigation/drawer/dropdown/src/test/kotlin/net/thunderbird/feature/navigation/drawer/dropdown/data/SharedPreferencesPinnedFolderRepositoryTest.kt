package net.thunderbird.feature.navigation.drawer.dropdown.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
private const val OTHER_ACCOUNT = "22222222-2222-4222-8222-222222222222"

@RunWith(RobolectricTestRunner::class)
internal class SharedPreferencesPinnedFolderRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing should be pinned at first`() = runTest {
        assertThat(SharedPreferencesPinnedFolderRepository(context).getPinnedFoldersFlow().first()).isEmpty()
    }

    @Test
    fun `pins should keep the order they were made in`() = runTest {
        // The drawer lists them in this order, so it is the user's order, not the folders' ids.
        val testSubject = SharedPreferencesPinnedFolderRepository(context)

        testSubject.pin(PinnedFolder(ACCOUNT, 9))
        testSubject.pin(PinnedFolder(OTHER_ACCOUNT, 1))
        testSubject.pin(PinnedFolder(ACCOUNT, 3))

        assertThat(testSubject.getPinnedFoldersFlow().first()).containsExactly(
            PinnedFolder(ACCOUNT, 9),
            PinnedFolder(OTHER_ACCOUNT, 1),
            PinnedFolder(ACCOUNT, 3),
        )
    }

    @Test
    fun `pinning twice should not list a folder twice`() = runTest {
        val testSubject = SharedPreferencesPinnedFolderRepository(context)

        testSubject.pin(PinnedFolder(ACCOUNT, 9))
        testSubject.pin(PinnedFolder(ACCOUNT, 9))

        assertThat(testSubject.getPinnedFoldersFlow().first()).containsExactly(PinnedFolder(ACCOUNT, 9))
    }

    @Test
    fun `the same folder id in another account should be a different pin`() {
        // Folder ids are per account database, so the id alone does not name a folder.
        val testSubject = SharedPreferencesPinnedFolderRepository(context)

        testSubject.pin(PinnedFolder(ACCOUNT, 1))

        assertThat(testSubject.isPinned(PinnedFolder(ACCOUNT, 1))).isTrue()
        assertThat(testSubject.isPinned(PinnedFolder(OTHER_ACCOUNT, 1))).isFalse()
    }

    @Test
    fun `unpinning should remove only that folder`() = runTest {
        val testSubject = SharedPreferencesPinnedFolderRepository(context)
        testSubject.pin(PinnedFolder(ACCOUNT, 1))
        testSubject.pin(PinnedFolder(ACCOUNT, 2))

        testSubject.unpin(PinnedFolder(ACCOUNT, 1))

        assertThat(testSubject.getPinnedFoldersFlow().first()).containsExactly(PinnedFolder(ACCOUNT, 2))
    }

    @Test
    fun `pins should survive the app being restarted`() = runTest {
        SharedPreferencesPinnedFolderRepository(context).apply {
            pin(PinnedFolder(ACCOUNT, 5))
            pin(PinnedFolder(OTHER_ACCOUNT, 6))
        }

        val reloaded = SharedPreferencesPinnedFolderRepository(context)

        assertThat(reloaded.getPinnedFoldersFlow().first())
            .containsExactly(PinnedFolder(ACCOUNT, 5), PinnedFolder(OTHER_ACCOUNT, 6))
    }

    @Test
    fun `an unreadable stored entry should be dropped rather than lose every pin`() = runTest {
        context.getSharedPreferences("drawer_pinned_folders", Context.MODE_PRIVATE).edit()
            .putString("pinned", "$ACCOUNT|5\ngarbage\n|7\n$OTHER_ACCOUNT|notanumber\n$OTHER_ACCOUNT|6")
            .commit()

        val testSubject = SharedPreferencesPinnedFolderRepository(context)

        assertThat(testSubject.getPinnedFoldersFlow().first())
            .containsExactly(PinnedFolder(ACCOUNT, 5), PinnedFolder(OTHER_ACCOUNT, 6))
    }
}
