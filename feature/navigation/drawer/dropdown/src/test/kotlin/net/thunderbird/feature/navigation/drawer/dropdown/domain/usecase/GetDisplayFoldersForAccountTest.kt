package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import app.cash.turbine.test
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.account.fake.FakeAccountData.ACCOUNT_ID_RAW
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.DisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.MailDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.PinnedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayAccount
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolderType
import net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase.FakeUnifiedFolderRepository.Companion.OTHER_UNIFIED_FOLDERS
import net.thunderbird.feature.navigation.drawer.dropdown.ui.FakeData
import app.k9mail.legacy.ui.folder.DisplayFolder as LegacyDisplayFolder

internal class GetDisplayFoldersForAccountTest {

    @Test
    fun `should return account folders when account id is regular`() = runTest {
        val accountId = ACCOUNT_ID_RAW
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderFlow = MutableStateFlow(DISPLAY_UNIFIED_FOLDER)
        val unifiedFolderRepository = FakeUnifiedFolderRepository(unifiedFolderFlow)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountDtoManager(),
        )

        val result = testSubject(accountId).first()

        assertThat(result).isEqualTo(DISPLAY_ACCOUNT_FOLDERS)
    }

    @Test
    fun `should return unifed account folders when account id is unified`() = runTest {
        val accountId = UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderFlow = MutableStateFlow(DISPLAY_UNIFIED_FOLDER)
        val unifiedFolderRepository = FakeUnifiedFolderRepository(unifiedFolderFlow)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountDtoManager(),
        )

        val result = testSubject(accountId).first()

        assertThat(result).isEqualTo(DISPLAY_UNIFIED_FOLDERS)
    }

    @Test
    fun `should only emit new list when account folders emit new items`() = runTest {
        val accountId = ACCOUNT_ID_RAW
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderFlow = MutableStateFlow(DISPLAY_UNIFIED_FOLDER)
        val unifiedFolderRepository = FakeUnifiedFolderRepository(unifiedFolderFlow)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountDtoManager(),
        )

        testSubject(accountId).test {
            assertThat(awaitItem()).isEqualTo(DISPLAY_ACCOUNT_FOLDERS)

            legacyDisplayFolderFlow.emit(LEGACY_DISPLAY_FOLDERS_2)

            assertThat(awaitItem()).isEqualTo(DISPLAY_ACCOUNT_FOLDERS_2)

            unifiedFolderFlow.emit(DISPLAY_UNIFIED_FOLDER_2)
        }
    }

    @Test
    fun `should only emit new list when unified account folders emit new items`() = runTest {
        val accountId = UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderFlow = MutableStateFlow(DISPLAY_UNIFIED_FOLDER)
        val unifiedFolderRepository = FakeUnifiedFolderRepository(unifiedFolderFlow)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountDtoManager(),
        )

        testSubject(accountId).test {
            assertThat(awaitItem()).isEqualTo(DISPLAY_UNIFIED_FOLDERS)

            legacyDisplayFolderFlow.emit(LEGACY_DISPLAY_FOLDERS_2)
            unifiedFolderFlow.emit(DISPLAY_UNIFIED_FOLDER_2)

            assertThat(awaitItem()).isEqualTo(listOf(DISPLAY_UNIFIED_FOLDER_2) + OTHER_UNIFIED_FOLDERS)
        }
    }

    @Test
    fun `should list every unified folder, inbox first`() = runTest {
        val testSubject = createUnifiedTestSubject()

        val result = testSubject(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first()

        assertThat(result.filterIsInstance<UnifiedDisplayFolder>().map { it.unifiedType })
            .containsExactly(*UnifiedDisplayFolderType.entries.toTypedArray())
    }

    @Test
    fun `should list pinned folders after the unified folders, in the order they were pinned`() = runTest {
        val pinned = FakePinnedFolderRepository(
            listOf(PinnedFolder(ACCOUNT_UUID_1, 2), PinnedFolder(ACCOUNT_UUID_1, 1)),
        )
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        val result = testSubject(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first()

        assertThat(result.drop(UnifiedDisplayFolderType.entries.size).map { (it as PinnedDisplayFolder).folder.id })
            .containsExactly(2L, 1L)
    }

    @Test
    fun `a pin should carry the folder counts`() = runTest {
        val pinned = FakePinnedFolderRepository(listOf(PinnedFolder(ACCOUNT_UUID_1, 2)))
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        val pin = testSubject(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first()
            .filterIsInstance<PinnedDisplayFolder>()
            .single()

        assertThat(pin.unreadMessageCount).isEqualTo(1)
        assertThat(pin.accountId).isEqualTo(ACCOUNT_UUID_1)
    }

    @Test
    fun `a pin should name its account only when there are accounts to tell apart`() = runTest {
        val pins = listOf(PinnedFolder(ACCOUNT_UUID_1, 1))

        val single = createUnifiedTestSubject(
            pinned = FakePinnedFolderRepository(pins),
            accounts = listOf(account(ACCOUNT_UUID_1, "Work")),
        ).invoke(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first().filterIsInstance<PinnedDisplayFolder>().single()

        val several = createUnifiedTestSubject(
            pinned = FakePinnedFolderRepository(pins),
            accounts = listOf(account(ACCOUNT_UUID_1, "Work"), account(ACCOUNT_UUID_2, "Home")),
        ).invoke(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first().filterIsInstance<PinnedDisplayFolder>().single()

        assertThat(single.accountName).isNull()
        assertThat(several.accountName).isEqualTo("Work")
    }

    @Test
    fun `a pin for a removed account should be skipped rather than fail the list`() = runTest {
        // Asking the folder repository for a missing account is an error, so the pin must never reach it.
        val pinned = FakePinnedFolderRepository(listOf(PinnedFolder(ACCOUNT_UUID_2, 1)))
        val testSubject = createUnifiedTestSubject(
            pinned = pinned,
            accounts = listOf(account(ACCOUNT_UUID_1, "Work")),
            displayFolderRepository = FailingForAccount(ACCOUNT_UUID_2),
        )

        val result = testSubject(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first()

        assertThat(result.filterIsInstance<PinnedDisplayFolder>()).isEmpty()
    }

    @Test
    fun `a pin for a folder that no longer exists should be skipped`() = runTest {
        val pinned = FakePinnedFolderRepository(listOf(PinnedFolder(ACCOUNT_UUID_1, 999)))
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        val result = testSubject(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).first()

        assertThat(result.filterIsInstance<PinnedDisplayFolder>()).isEmpty()
    }

    @Test
    fun `pinning and unpinning should update the list`() = runTest {
        val pinned = FakePinnedFolderRepository()
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        testSubject(UnifiedDisplayAccount.UNIFIED_ACCOUNT_ID).test {
            assertThat(awaitItem().filterIsInstance<PinnedDisplayFolder>()).isEmpty()

            pinned.pin(PinnedFolder(ACCOUNT_UUID_1, 1))
            assertThat(awaitItem().filterIsInstance<PinnedDisplayFolder>().map { it.folder.id }).containsExactly(1L)

            pinned.unpin(PinnedFolder(ACCOUNT_UUID_1, 1))
            assertThat(awaitItem().filterIsInstance<PinnedDisplayFolder>()).isEmpty()
        }
    }

    private fun createUnifiedTestSubject(
        pinned: FakePinnedFolderRepository = FakePinnedFolderRepository(),
        accounts: List<LegacyAccountDto> = emptyList(),
        displayFolderRepository: DisplayFolderRepository = FakeDisplayFolderRepository(
            MutableStateFlow(LEGACY_DISPLAY_FOLDERS),
        ),
    ) = GetDisplayFoldersForAccount(
        displayFolderRepository = displayFolderRepository,
        unifiedFolderRepository = FakeUnifiedFolderRepository(MutableStateFlow(DISPLAY_UNIFIED_FOLDER)),
        pinnedFolderRepository = pinned,
        accountManager = FakeLegacyAccountDtoManager(accounts = accounts),
    )

    private fun account(uuid: String, name: String) = LegacyAccountDto(uuid).apply { this.name = name }

    /**
     * Fails for one account, the way the real repository does for an account that does not exist.
     */
    private class FailingForAccount(private val missingAccountUuid: String) : DisplayFolderRepository {
        override fun getDisplayFoldersFlow(
            account: LegacyAccountDto,
            includeHiddenFolders: Boolean,
        ): Flow<List<LegacyDisplayFolder>> = error("Not used")

        override fun getDisplayFoldersFlow(accountUuid: String): Flow<List<LegacyDisplayFolder>> {
            check(accountUuid != missingAccountUuid) { "Account not found: $accountUuid" }
            return MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        }
    }

    private companion object {
        const val ACCOUNT_UUID_1 = "11111111-1111-4111-8111-111111111111"
        const val ACCOUNT_UUID_2 = "22222222-2222-4222-8222-222222222222"

        val LEGACY_DISPLAY_FOLDERS = listOf(
            LegacyDisplayFolder(
                folder = FakeData.FOLDER,
                isInTopGroup = false,
                unreadMessageCount = 0,
                starredMessageCount = 0,
                pathDelimiter = "/",
            ),
            LegacyDisplayFolder(
                folder = FakeData.FOLDER.copy(
                    id = 2,
                    name = "Folder 2",
                ),
                isInTopGroup = false,
                unreadMessageCount = 1,
                starredMessageCount = 0,
                pathDelimiter = "/",
            ),
        )

        val LEGACY_DISPLAY_FOLDERS_2 = LEGACY_DISPLAY_FOLDERS + LegacyDisplayFolder(
            folder = FakeData.FOLDER.copy(
                id = 3,
                name = "Folder 3",
            ),
            isInTopGroup = false,
            unreadMessageCount = 0,
            starredMessageCount = 0,
            pathDelimiter = "/",
        )

        val DISPLAY_UNIFIED_FOLDER = UnifiedDisplayFolder(
            id = "unified_inbox",
            unifiedType = UnifiedDisplayFolderType.INBOX,
            unreadMessageCount = 2,
            starredMessageCount = 2,
        )

        val DISPLAY_UNIFIED_FOLDER_2 = UnifiedDisplayFolder(
            id = "unified_inbox",
            unifiedType = UnifiedDisplayFolderType.INBOX,
            unreadMessageCount = 3,
            starredMessageCount = 3,
        )

        val DISPLAY_UNIFIED_FOLDERS = listOf(DISPLAY_UNIFIED_FOLDER) + OTHER_UNIFIED_FOLDERS

        val DISPLAY_ACCOUNT_FOLDERS = listOf<DisplayFolder>(
            MailDisplayFolder(
                accountId = ACCOUNT_ID_RAW,
                folder = FakeData.FOLDER,
                isInTopGroup = false,
                unreadMessageCount = 0,
                starredMessageCount = 0,
                pathDelimiter = "/",
            ),
            MailDisplayFolder(
                accountId = ACCOUNT_ID_RAW,
                folder = FakeData.FOLDER.copy(
                    id = 2,
                    name = "Folder 2",
                ),
                isInTopGroup = false,
                unreadMessageCount = 1,
                starredMessageCount = 0,
                pathDelimiter = "/",
            ),
        )

        val DISPLAY_ACCOUNT_FOLDERS_2 = DISPLAY_ACCOUNT_FOLDERS + MailDisplayFolder(
            accountId = ACCOUNT_ID_RAW,
            folder = FakeData.FOLDER.copy(
                id = 3,
                name = "Folder 3",
            ),
            isInTopGroup = false,
            unreadMessageCount = 0,
            starredMessageCount = 0,
            pathDelimiter = "/",
        )
    }
}
