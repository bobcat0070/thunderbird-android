package com.fsck.k9.mailstore.recipients

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

private const val LIMIT = 10

class RecipientIndexTest : RobolectricTest() {
    private val testSubject = RecipientIndex(ApplicationProvider.getApplicationContext())

    @Test
    fun `an address written to should be found by its prefix`() {
        testSubject.recordSent("sam@example.com", "Sam Vimes", at = 1000)

        assertThat(testSubject.search("sam", LIMIT).map { it.address }).containsExactly("sam@example.com")
    }

    @Test
    fun `an address should be found by the start of a name`() {
        testSubject.recordSent("sv@example.com", "Sam Vimes", at = 1000)

        assertThat(testSubject.search("Sam", LIMIT).map { it.address }).containsExactly("sv@example.com")
    }

    @Test
    fun `an address should be found by a surname`() {
        // Typing a surname is ordinary, and a name-prefix-only match would never find it.
        testSubject.recordSent("sv@example.com", "Sam Vimes", at = 1000)

        assertThat(testSubject.search("Vim", LIMIT).map { it.address }).containsExactly("sv@example.com")
    }

    @Test
    fun `a match in the middle of a word should not count`() {
        // Otherwise typing "sam" offers everyone whose address merely contains it.
        testSubject.recordSent("notsam@example.com", null, at = 1000)

        assertThat(testSubject.search("sam", LIMIT)).isEmpty()
    }

    @Test
    fun `an address written to more often should rank first`() {
        // Both match the prefix, so only the send count can order them - and the one written to twice wins
        // despite the other being more recent.
        testSubject.recordSent("orwell@example.com", null, at = 5000)
        testSubject.recordSent("often@example.com", null, at = 1000)
        testSubject.recordSent("often@example.com", null, at = 2000)

        assertThat(testSubject.search("o", LIMIT).map { it.address })
            .containsExactly("often@example.com", "orwell@example.com")
    }

    @Test
    fun `an address matching itself should outrank one matching only by name`() {
        testSubject.recordSent("other@example.com", "Sam Vimes", at = 1000)
        testSubject.recordSent("other@example.com", null, at = 1000)
        testSubject.recordSent("sam@example.com", null, at = 1000)

        assertThat(testSubject.search("sam", LIMIT).first().address).isEqualTo("sam@example.com")
    }

    @Test
    fun `sending twice should count twice`() {
        testSubject.recordSent("sam@example.com", null, at = 1000)
        testSubject.recordSent("sam@example.com", null, at = 2000)

        assertThat(testSubject.timesSentTo("sam@example.com")).isEqualTo(2)
    }

    @Test
    fun `an address should be matched whatever case it was recorded in`() {
        testSubject.recordSent("Sam@Example.COM", null, at = 1000)

        assertThat(testSubject.timesSentTo("sam@example.com")).isEqualTo(1)
        assertThat(testSubject.search("SAM@", LIMIT).map { it.address }).containsExactly("sam@example.com")
    }

    @Test
    fun `a name already known should not be replaced by a message without one`() {
        // Bulk mail often carries no display name, and losing the name of a person to it would be a regression
        // the user sees.
        testSubject.recordSent("sam@example.com", "Sam Vimes", at = 1000)
        testSubject.recordSent("sam@example.com", null, at = 2000)

        assertThat(testSubject.search("sam", LIMIT).first().displayName).isEqualTo("Sam Vimes")
    }

    @Test
    fun `something that is not an address should be ignored`() {
        testSubject.recordSent("not-an-address", null, at = 1000)
        testSubject.recordSent("   ", null, at = 1000)

        assertThat(testSubject.search("not", LIMIT)).isEmpty()
    }

    @Test
    fun `a wildcard typed by the user should be searched for literally`() {
        testSubject.recordSent("sam@example.com", null, at = 1000)

        assertThat(testSubject.search("%", LIMIT)).isEmpty()
    }

    @Test
    fun `a remote contact should be completable before anyone writes to it`() {
        testSubject.recordRemoteContacts("account-1", listOf(RemoteContact("colleague@example.com", "A Colleague")))

        val found = testSubject.search("colleague", LIMIT)

        assertThat(found.map { it.address }).containsExactly("colleague@example.com")
        assertThat(found.first().origin).isEqualTo(RecipientOrigin.REMOTE)
    }

    @Test
    fun `a remote contact should not be offered as a suggestion before anything is typed`() {
        // Existing is not the same as corresponding with, and a directory of thousands would drown the few
        // people the user actually writes to.
        testSubject.recordRemoteContacts("account-1", listOf(RemoteContact("colleague@example.com", null)))
        testSubject.recordSent("sam@example.com", null, at = 1000)

        assertThat(testSubject.mostUsed(LIMIT).map { it.address }).containsExactly("sam@example.com")
    }

    @Test
    fun `a remote contact should not reset a send count`() {
        testSubject.recordSent("sam@example.com", null, at = 1000)
        testSubject.recordRemoteContacts("account-1", listOf(RemoteContact("sam@example.com", "Sam Vimes")))

        assertThat(testSubject.timesSentTo("sam@example.com")).isEqualTo(1)
        assertThat(testSubject.search("sam", LIMIT).first().displayName).isEqualTo("Sam Vimes")
    }

    @Test
    fun `a removed remote contact should stop being offered`() {
        testSubject.recordRemoteContacts("account-1", listOf(RemoteContact("gone@example.com", null)))

        testSubject.removeRemoteContacts("account-1", listOf("gone@example.com"))

        assertThat(testSubject.search("gone", LIMIT)).isEmpty()
    }

    @Test
    fun `a removed remote contact that was written to should be kept`() {
        // The address book no longer listing someone is no reason to stop completing an address in use.
        testSubject.recordRemoteContacts("account-1", listOf(RemoteContact("sam@example.com", "Sam Vimes")))
        testSubject.recordSent("sam@example.com", null, at = 1000)

        testSubject.removeRemoteContacts("account-1", listOf("sam@example.com"))

        assertThat(testSubject.search("sam", LIMIT).map { it.address }).containsExactly("sam@example.com")
    }

    @Test
    fun `removing an account should take its unused contacts with it`() {
        testSubject.recordRemoteContacts("account-1", listOf(RemoteContact("colleague@example.com", null)))
        testSubject.setSyncState("account-1", "delta", "token")

        testSubject.removeAccount("account-1")

        assertThat(testSubject.search("colleague", LIMIT)).isEmpty()
        assertThat(testSubject.syncState("account-1", "delta")).isNull()
    }

    @Test
    fun `addresses written to enough should be the ones reported as corresponded with`() {
        testSubject.recordSent("once@example.com", null, at = 1000)
        testSubject.recordSent("twice@example.com", null, at = 1000)
        testSubject.recordSent("twice@example.com", null, at = 2000)

        assertThat(testSubject.addressesWrittenTo(minimumMessages = 2).toList()).containsExactly("twice@example.com")
    }

    @Test
    fun `sync state should be remembered and replaced`() {
        testSubject.setSyncState("account-1", "delta", "first")
        testSubject.setSyncState("account-1", "delta", "second")

        assertThat(testSubject.syncState("account-1", "delta")).isEqualTo("second")
        assertThat(testSubject.syncState("account-1", "other")).isNull()
    }

    @Test
    fun `the number of results should be capped`() {
        repeat(5) { index -> testSubject.recordSent("person$index@example.com", null, at = 1000) }

        assertThat(testSubject.search("person", limit = 3)).hasSize(3)
    }
}
