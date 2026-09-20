package com.fsck.k9.backends

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mailstore.recipients.RecipientIndex
import net.thunderbird.backend.graph.command.GraphContactUpdate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val ACCOUNT_UUID = "account-1"
private const val HOUR_MILLIS = 60L * 60L * 1000L
private const val LIMIT = 10

@RunWith(RobolectricTestRunner::class)
class GraphContactIndexStoreTest {
    private val index = RecipientIndex(ApplicationProvider.getApplicationContext())
    private var now = 1_000_000L
    private val testSubject = GraphContactIndexStore(ACCOUNT_UUID, index) { now }

    @Test
    fun `a contact should become completable`() {
        testSubject.onContactsChanged(
            listOf(GraphContactUpdate("Sam Vimes", listOf("sam@example.com"), isRemoved = false)),
        )

        assertThat(index.search("sam", LIMIT).map { it.address }).containsExactly("sam@example.com")
    }

    @Test
    fun `every address of a contact should be completable`() {
        // A directory entry commonly carries a work and an alias address, and either may be the one meant.
        testSubject.onContactsChanged(
            listOf(
                GraphContactUpdate("Sam Vimes", listOf("sam@example.com", "s.vimes@example.com"), isRemoved = false),
            ),
        )

        assertThat(index.search("Vimes", LIMIT).map { it.address })
            .containsExactly("s.vimes@example.com", "sam@example.com")
    }

    @Test
    fun `a removed contact should stop being completable`() {
        testSubject.onContactsChanged(
            listOf(GraphContactUpdate(null, listOf("gone@example.com"), isRemoved = false)),
        )

        testSubject.onContactsChanged(
            listOf(GraphContactUpdate(null, listOf("gone@example.com"), isRemoved = true)),
        )

        assertThat(index.search("gone", LIMIT)).isEmpty()
    }

    @Test
    fun `a first sync should be due and the next one should not`() {
        assertThat(testSubject.isContactSyncDue()).isTrue()

        testSubject.saveContactsDeltaLink("link")

        assertThat(testSubject.isContactSyncDue()).isFalse()
    }

    @Test
    fun `a sync should be due again after an hour`() {
        testSubject.saveContactsDeltaLink("link")

        now += HOUR_MILLIS

        assertThat(testSubject.isContactSyncDue()).isTrue()
    }

    @Test
    fun `the delta link should survive for the next sync`() {
        testSubject.saveContactsDeltaLink("link")

        assertThat(testSubject.contactsDeltaLink()).isEqualTo("link")
    }

    @Test
    fun `contacts of one account should not be removed by another`() {
        val other = GraphContactIndexStore("account-2", index) { now }
        testSubject.onContactsChanged(
            listOf(GraphContactUpdate(null, listOf("mine@example.com"), isRemoved = false)),
        )

        other.onContactsChanged(listOf(GraphContactUpdate(null, listOf("mine@example.com"), isRemoved = true)))

        assertThat(index.search("mine", LIMIT).map { it.address }).containsExactly("mine@example.com")
    }
}
