package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageDetailsAccessor
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import app.k9mail.legacy.mailstore.MessageMapper
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.Address
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

private const val SENT_FOLDER_ID = 7L
private const val OTHER_SENT_FOLDER_ID = 9L
private const val MINUTE_MILLIS = 60L * 1000L

class KnownCorrespondentsTest {
    private val repository = FakeMessageListRepository()
    private var now = 1_000_000L

    @Test
    fun `an address never written to should not be known`() {
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))

        assertThat(knownCorrespondents().isKnown("stranger@example.com")).isFalse()
    }

    @Test
    fun `an address written to twice should be known`() {
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))

        assertThat(knownCorrespondents().isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `an address written to once should not be known`() {
        // Replying once to a marketing address is ordinary, and one reply would otherwise promote everything
        // that sender ever sends above its own bulk headers.
        sentMessages("account", to = listOf("news@shop.example"))

        assertThat(knownCorrespondents().isKnown("news@shop.example")).isFalse()
    }

    @Test
    fun `an address named twice in one message should count once`() {
        // That is one message, not two - otherwise addressing a single mail to someone in both To and Cc
        // would be enough to make them a correspondent.
        repository.messages(accountUuid = "account", folderId = SENT_FOLDER_ID) {
            listOf(message(to = listOf("sam@example.com"), cc = listOf("sam@example.com")))
        }

        assertThat(knownCorrespondents().isKnown("sam@example.com")).isFalse()
    }

    @Test
    fun `recipients in Cc should count`() {
        repository.messages(accountUuid = "account", folderId = SENT_FOLDER_ID) {
            listOf(
                message(to = listOf("kim@example.com"), cc = listOf("sam@example.com")),
                message(to = listOf("sam@example.com")),
            )
        }

        assertThat(knownCorrespondents().isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `matching should ignore case and surrounding space`() {
        sentMessages("account", to = listOf("Sam@Example.COM", "sam@example.com"))

        assertThat(knownCorrespondents().isKnown("  SAM@example.com  ")).isTrue()
    }

    @Test
    fun `a blank address should not be known`() {
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))

        assertThat(knownCorrespondents().isKnown("   ")).isFalse()
    }

    @Test
    fun `messages should be counted across accounts`() {
        // Writing to someone from either address is still corresponding with them.
        sentMessages("work", to = listOf("sam@example.com"))
        sentMessages("personal", folderId = OTHER_SENT_FOLDER_ID, to = listOf("sam@example.com"))

        val testSubject = knownCorrespondents(
            account("work", SENT_FOLDER_ID),
            account("personal", OTHER_SENT_FOLDER_ID),
        )

        assertThat(testSubject.isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `an account with no sent folder should be skipped`() {
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))

        val testSubject = knownCorrespondents(account("nosent", sentFolderId = null), account("account"))

        assertThat(testSubject.isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `an unreadable account should not lose the answer for the others`() {
        // The worst case has to be one missing signal, never a classification that fails outright.
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))
        repository.failFor("broken")

        val testSubject = knownCorrespondents(account("broken", OTHER_SENT_FOLDER_ID), account("account"))

        assertThat(testSubject.isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `a result should be reused rather than re-read`() {
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))
        val testSubject = knownCorrespondents()

        repeat(times = 5) { testSubject.isKnown("sam@example.com") }

        assertThat(repository.reads).isEqualTo(1)
    }

    @Test
    fun `a result should be re-read once it is stale`() {
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))
        val testSubject = knownCorrespondents()
        testSubject.isKnown("sam@example.com")

        now += 31 * MINUTE_MILLIS

        testSubject.isKnown("sam@example.com")
        assertThat(repository.reads).isEqualTo(2)
    }

    @Test
    fun `an empty result should be re-read soon`() {
        // Empty usually means the sent folder has not synchronised yet rather than that the user has never
        // written to anyone. Holding that for half an hour would ignore the signal for the first half hour
        // of an account's life, which is exactly when a mailbox is being filled and classified.
        val testSubject = knownCorrespondents()
        testSubject.isKnown("sam@example.com")

        now += 2 * MINUTE_MILLIS
        sentMessages("account", to = listOf("sam@example.com", "sam@example.com"))

        assertThat(testSubject.isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `an empty result should still be reused briefly`() {
        val testSubject = knownCorrespondents()
        testSubject.isKnown("sam@example.com")

        testSubject.isKnown("sam@example.com")

        assertThat(repository.reads).isEqualTo(1)
    }

    private fun knownCorrespondents(vararg accounts: LegacyAccountDto): KnownCorrespondents {
        val configured = if (accounts.isEmpty()) arrayOf(account("account")) else accounts
        val accountManager = mock<LegacyAccountDtoManager> {
            on { getAccounts() } doReturn configured.toList()
        }

        return KnownCorrespondents(accountManager, repository) { now }
    }

    private fun account(uuid: String, sentFolderId: Long? = SENT_FOLDER_ID): LegacyAccountDto {
        return mock {
            on { this.uuid } doReturn uuid
            on { this.sentFolderId } doReturn sentFolderId
        }
    }

    private fun sentMessages(accountUuid: String, folderId: Long = SENT_FOLDER_ID, to: List<String>) {
        repository.messages(accountUuid, folderId) { to.map { address -> message(to = listOf(address)) } }
    }

    private fun message(to: List<String>, cc: List<String> = emptyList()): MessageDetailsAccessor {
        return mock {
            on { toAddresses } doReturn to.map { Address(it) }
            on { ccAddresses } doReturn cc.map { Address(it) }
        }
    }
}

/**
 * Answers only the query this makes, and records how often it was asked - which is the point of the cache.
 */
private class FakeMessageListRepository : MessageListRepository {
    private val messagesByFolder = mutableMapOf<Pair<String, Long>, () -> List<MessageDetailsAccessor>>()
    private val failing = mutableSetOf<String>()
    var reads = 0

    fun messages(accountUuid: String, folderId: Long, messages: () -> List<MessageDetailsAccessor>) {
        messagesByFolder[accountUuid to folderId] = messages
    }

    fun failFor(accountUuid: String) {
        failing += accountUuid
    }

    override fun <T> getMessages(
        accountUuid: String,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        messageMapper: MessageMapper<T>,
    ): List<T> {
        reads++
        if (accountUuid in failing) throw IllegalStateException("database unavailable")

        val folderId = selectionArgs.single().toLong()

        return messagesByFolder[accountUuid to folderId].orEmpty().map { messageMapper.map(it) }
    }

    private fun (() -> List<MessageDetailsAccessor>)?.orEmpty() = this?.invoke() ?: emptyList()

    override fun addListener(listener: MessageListChangedListener) = Unit
    override fun addListener(accountUuid: String, listener: MessageListChangedListener) = Unit
    override fun removeListener(listener: MessageListChangedListener) = Unit
    override fun notifyMessageListChanged(accountUuid: String) = Unit

    override fun <T> getThreadedMessages(
        accountUuid: String,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        messageMapper: MessageMapper<T>,
    ): List<T> = throw UnsupportedOperationException("not used")

    override fun <T> getThread(
        accountUuid: String,
        threadId: Long,
        sortOrder: String,
        messageMapper: MessageMapper<T>,
    ): List<T> = throw UnsupportedOperationException("not used")
}
