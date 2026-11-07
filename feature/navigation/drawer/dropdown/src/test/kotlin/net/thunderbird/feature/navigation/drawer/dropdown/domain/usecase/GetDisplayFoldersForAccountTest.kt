package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import app.cash.turbine.test
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.thunderbird.account.fake.FakeAccountData.ACCOUNT_ID
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.account.UnifiedAccountId
import net.thunderbird.feature.account.storage.profile.AvatarDto
import net.thunderbird.feature.account.storage.profile.AvatarTypeDto
import net.thunderbird.feature.account.storage.profile.ProfileDto
import net.thunderbird.feature.mail.folder.FolderRole
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.PinnedFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.DisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.MailDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.PinnedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.domain.entity.UnifiedDisplayFolder
import net.thunderbird.feature.navigation.drawer.dropdown.ui.FakeData
import app.k9mail.legacy.ui.folder.DisplayFolder as LegacyDisplayFolder

internal class GetDisplayFoldersForAccountTest {

    @Test
    fun `should return account folders when account id is regular`() = runTest {
        val accountId = ACCOUNT_ID
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = FakeUnifiedFolderRepository(),
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountManager(),
        )

        val result = testSubject(accountId).first()

        assertThat(result).isEqualTo(DISPLAY_ACCOUNT_FOLDERS)
    }

    @Test
    fun `should return all unified folders in drawer order when account id is unified`() = runTest {
        val accountId = UnifiedAccountId
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderRepository = FakeUnifiedFolderRepository(createUnifiedFolderFlows())
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountManager(),
        )

        val result = testSubject(accountId).first()

        assertThat(result).isEqualTo(
            listOf(
                createUnifiedFolder(FolderRole.INBOX),
                createUnifiedFolder(FolderRole.OUTBOX),
                createUnifiedFolder(FolderRole.DRAFTS),
                createUnifiedFolder(FolderRole.SENT),
                createUnifiedFolder(FolderRole.ARCHIVE),
                createUnifiedFolder(FolderRole.SPAM),
                createUnifiedFolder(FolderRole.TRASH),
            ),
        )
    }

    @Test
    fun `should only emit new list when account folders emit new items`() = runTest {
        val accountId = ACCOUNT_ID
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderFlows = createUnifiedFolderFlows()
        val unifiedFolderRepository = FakeUnifiedFolderRepository(unifiedFolderFlows)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountManager(),
        )

        testSubject(accountId).test {
            assertThat(awaitItem()).isEqualTo(DISPLAY_ACCOUNT_FOLDERS)

            legacyDisplayFolderFlow.emit(LEGACY_DISPLAY_FOLDERS_2)

            assertThat(awaitItem()).isEqualTo(DISPLAY_ACCOUNT_FOLDERS_2)

            unifiedFolderFlows.getValue(FolderRole.INBOX).emit(
                createUnifiedFolder(FolderRole.INBOX, unreadMessageCount = 3),
            )
        }
    }

    @Test
    fun `should only emit new list when unified account folders emit new items`() = runTest {
        val accountId = UnifiedAccountId
        val legacyDisplayFolderFlow = MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        val displayFolderRepository = FakeDisplayFolderRepository(legacyDisplayFolderFlow)
        val unifiedFolderFlows = createUnifiedFolderFlows()
        val unifiedFolderRepository = FakeUnifiedFolderRepository(unifiedFolderFlows)
        val testSubject = GetDisplayFoldersForAccount(
            displayFolderRepository = displayFolderRepository,
            unifiedFolderRepository = unifiedFolderRepository,
            pinnedFolderRepository = FakePinnedFolderRepository(),
            accountManager = FakeLegacyAccountManager(),
        )
        val updatedSentFolder = createUnifiedFolder(FolderRole.SENT, unreadMessageCount = 3)

        testSubject(accountId).test {
            val initialFolders = awaitItem()

            legacyDisplayFolderFlow.emit(LEGACY_DISPLAY_FOLDERS_2)
            unifiedFolderFlows.getValue(FolderRole.SENT).emit(updatedSentFolder)

            assertThat(awaitItem()).isEqualTo(
                initialFolders.map { if (it.folderId == updatedSentFolder.folderId) updatedSentFolder else it },
            )
        }
    }

    @Test
    fun `should list pinned folders after the unified folders, in the order they were pinned`() = runTest {
        val pinned = FakePinnedFolderRepository(
            listOf(PinnedFolder(ACCOUNT_UUID_1, 2), PinnedFolder(ACCOUNT_UUID_1, 1)),
        )
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        val result = testSubject(UnifiedAccountId).first()

        assertThat(result.drop(FolderRole.entries.size).map { (it as PinnedDisplayFolder).folder.id })
            .containsExactly(2L, 1L)
    }

    @Test
    fun `a pin should carry the folder counts`() = runTest {
        val pinned = FakePinnedFolderRepository(listOf(PinnedFolder(ACCOUNT_UUID_1, 2)))
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        val pin = testSubject(UnifiedAccountId).first()
            .filterIsInstance<PinnedDisplayFolder>()
            .single()

        assertThat(pin.unreadMessageCount).isEqualTo(1)
        assertThat(pin.accountId).isEqualTo(AccountIdFactory.of(ACCOUNT_UUID_1))
    }

    @Test
    fun `a pin should name its account only when there are accounts to tell apart`() = runTest {
        val pins = listOf(PinnedFolder(ACCOUNT_UUID_1, 1))

        val single = createUnifiedTestSubject(
            pinned = FakePinnedFolderRepository(pins),
            accounts = listOf(account(ACCOUNT_UUID_1, "Work")),
        ).invoke(UnifiedAccountId).first().filterIsInstance<PinnedDisplayFolder>().single()

        val several = createUnifiedTestSubject(
            pinned = FakePinnedFolderRepository(pins),
            accounts = listOf(account(ACCOUNT_UUID_1, "Work"), account(ACCOUNT_UUID_2, "Home")),
        ).invoke(UnifiedAccountId).first().filterIsInstance<PinnedDisplayFolder>().single()

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

        val result = testSubject(UnifiedAccountId).first()

        assertThat(result.filterIsInstance<PinnedDisplayFolder>()).isEmpty()
    }

    @Test
    fun `a pin for a folder that no longer exists should be skipped`() = runTest {
        val pinned = FakePinnedFolderRepository(listOf(PinnedFolder(ACCOUNT_UUID_1, 999)))
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        val result = testSubject(UnifiedAccountId).first()

        assertThat(result.filterIsInstance<PinnedDisplayFolder>()).isEmpty()
    }

    @Test
    fun `pinning and unpinning should update the list`() = runTest {
        val pinned = FakePinnedFolderRepository()
        val testSubject = createUnifiedTestSubject(pinned = pinned, accounts = listOf(account(ACCOUNT_UUID_1, "Work")))

        testSubject(UnifiedAccountId).test {
            assertThat(awaitItem().filterIsInstance<PinnedDisplayFolder>()).isEmpty()

            pinned.pin(PinnedFolder(ACCOUNT_UUID_1, 1))
            assertThat(awaitItem().filterIsInstance<PinnedDisplayFolder>().map { it.folder.id }).containsExactly(1L)

            pinned.unpin(PinnedFolder(ACCOUNT_UUID_1, 1))
            assertThat(awaitItem().filterIsInstance<PinnedDisplayFolder>()).isEmpty()
        }
    }

    private fun createUnifiedTestSubject(
        pinned: FakePinnedFolderRepository = FakePinnedFolderRepository(),
        accounts: List<LegacyAccount> = emptyList(),
        displayFolderRepository: DisplayFolderRepository = FakeDisplayFolderRepository(
            MutableStateFlow(LEGACY_DISPLAY_FOLDERS),
        ),
    ) = GetDisplayFoldersForAccount(
        displayFolderRepository = displayFolderRepository,
        unifiedFolderRepository = FakeUnifiedFolderRepository(),
        pinnedFolderRepository = pinned,
        accountManager = FakeLegacyAccountManager(initialAccounts = accounts),
    )

    private fun account(uuid: String, name: String): LegacyAccount {
        val accountId = AccountIdFactory.of(uuid)
        val serverSettings = ServerSettings(
            type = "dummy",
            host = "",
            port = -1,
            connectionSecurity = ConnectionSecurity.NONE,
            authenticationType = AuthType.PLAIN,
            username = "",
            password = "",
            clientCertificateAlias = null,
        )
        return LegacyAccount(
            id = accountId,
            name = name,
            email = "$name@example.org",
            profile = ProfileDto(
                id = accountId,
                name = name,
                color = 0,
                avatar = AvatarDto(
                    accountId = accountId,
                    avatarType = AvatarTypeDto.MONOGRAM,
                    avatarMonogram = null,
                    avatarImageUri = null,
                    avatarIconName = null,
                ),
            ),
            incomingServerSettings = serverSettings,
            outgoingServerSettings = serverSettings,
            identities = listOf(Identity(email = "$name@example.org")),
        )
    }

    /**
     * Fails for one account, the way the real repository does for an account that does not exist.
     */
    private class FailingForAccount(private val missingAccountUuid: String) : DisplayFolderRepository {
        override fun getDisplayFoldersFlow(
            accountId: AccountId,
            includeHiddenFolders: Boolean,
        ): Flow<List<LegacyDisplayFolder>> = error("Not used")

        override fun getDisplayFoldersFlow(accountId: AccountId): Flow<List<LegacyDisplayFolder>> {
            check(accountId.toString() != missingAccountUuid) { "Account not found: $accountId" }
            return MutableStateFlow(LEGACY_DISPLAY_FOLDERS)
        }
    }

    private fun createUnifiedFolderFlows(): Map<FolderRole, MutableStateFlow<UnifiedDisplayFolder>> {
        return FolderRole.entries.associateWith { MutableStateFlow(createUnifiedFolder(it)) }
    }

    private fun createUnifiedFolder(
        role: FolderRole,
        unreadMessageCount: Int = 2,
    ): UnifiedDisplayFolder {
        return UnifiedDisplayFolder(
            role = role,
            unreadMessageCount = unreadMessageCount,
            starredMessageCount = 2,
        )
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

        val DISPLAY_ACCOUNT_FOLDERS = listOf<DisplayFolder>(
            MailDisplayFolder(
                accountId = ACCOUNT_ID,
                folder = FakeData.FOLDER,
                isInTopGroup = false,
                unreadMessageCount = 0,
                starredMessageCount = 0,
                pathDelimiter = "/",
            ),
            MailDisplayFolder(
                accountId = ACCOUNT_ID,
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
            accountId = ACCOUNT_ID,
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
