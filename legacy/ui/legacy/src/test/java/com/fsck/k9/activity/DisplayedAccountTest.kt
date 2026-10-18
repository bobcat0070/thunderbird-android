package com.fsck.k9.activity

import assertk.assertThat
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.fsck.k9.FakeLegacyAccount
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.SearchAccount
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.feature.search.legacy.createUnifiedFolderSearch
import org.junit.Test

class DisplayedAccountTest {
    private val firstAccount = FakeLegacyAccount.create(AccountIdFactory.create())
    private val secondAccount = FakeLegacyAccount.create(AccountIdFactory.create())
    private val accountManager = FakeLegacyAccountManager(listOf(firstAccount, secondAccount))

    @Test
    fun `switching to the unified inbox should keep the current account`() {
        // Without one, the drawer is never told the unified account is now shown.
        val search = SearchAccount.createUnifiedInboxSearch(title = "Unified Inbox", detail = "").relatedSearch

        val testSubject = search.displayedAccount(currentAccount = firstAccount, accountManager = accountManager)

        assertThat(testSubject).isSameInstanceAs(firstAccount)
    }

    @Test
    fun `switching to another unified folder should keep the current account`() {
        val search = createUnifiedFolderSearch(UnifiedFolderKind.SENT)

        val testSubject = search.displayedAccount(currentAccount = firstAccount, accountManager = accountManager)

        assertThat(testSubject).isSameInstanceAs(firstAccount)
    }

    @Test
    fun `a search of one account should show that account`() {
        val search = LocalMessageSearch().apply { addAccountId(secondAccount.id) }

        val testSubject = search.displayedAccount(currentAccount = firstAccount, accountManager = accountManager)

        assertThat(testSubject).isSameInstanceAs(secondAccount)
    }

    @Test
    fun `a search of every account that is not a unified view should show none`() {
        // As upstream decides for such searches, e.g. a search typed across all accounts.
        val search = LocalMessageSearch()

        val testSubject = search.displayedAccount(currentAccount = firstAccount, accountManager = accountManager)

        assertThat(testSubject).isNull()
    }
}
