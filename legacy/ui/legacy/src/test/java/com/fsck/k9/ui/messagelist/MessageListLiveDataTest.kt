package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.message.controller.MessageReference
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.SortType
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import net.thunderbird.feature.account.AccountIdFactory

class MessageListLiveDataTest {

    @Test
    fun `the newest messages should stand in for the list when no message is being read`() {
        val testSubject = MessageListInfo(listOf(item(1), item(2)), hasMoreMessages = false)

        val result = testSubject.canStandInForWholeList(activeMessage = null)

        assertThat(result).isTrue()
    }

    @Test
    fun `the newest messages should stand in for the list when they hold the message being read`() {
        val testSubject = MessageListInfo(listOf(item(1), item(2)), hasMoreMessages = false)

        val result = testSubject.canStandInForWholeList(activeMessage = reference(2))

        assertThat(result).isTrue()
    }

    @Test
    fun `the newest messages should not stand in for the list when the message being read is older`() {
        // The reader closes when the list beside it lacks its message, so this short list closed an older message
        // the moment it was opened.
        val testSubject = MessageListInfo(listOf(item(1), item(2)), hasMoreMessages = false)

        val result = testSubject.canStandInForWholeList(activeMessage = reference(3))

        assertThat(result).isFalse()
    }

    @Test
    fun `a list should be the same when only the message being read changed`() {
        // Going back to the list from a message: the newest messages stood in for the list on screen, cut it short,
        // and the reader lost their place in it.
        val testSubject = config(activeMessage = null)

        val result = testSubject.isSameListAs(
            config(
                activeMessage = reference(2),
                sortOverrides = mapOf(reference(2) to MessageSortOverride(isRead = false, isStarred = false)),
            ),
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `a list should not be the same when it is sorted differently`() {
        val testSubject = config(sortType = SortType.SORT_DATE)

        val result = testSubject.isSameListAs(config(sortType = SortType.SORT_SUBJECT))

        assertThat(result).isFalse()
    }

    @Test
    fun `a list should not be the same as another search`() {
        val testSubject = config()

        val result = testSubject.isSameListAs(config(search = LocalMessageSearch()))

        assertThat(result).isFalse()
    }

    @Test
    fun `a list should not be the same as no list`() {
        val testSubject = config()

        val result = testSubject.isSameListAs(null)

        assertThat(result).isFalse()
    }

    private fun config(
        search: LocalMessageSearch = SEARCH,
        sortType: SortType = SortType.SORT_DATE,
        activeMessage: MessageReference? = null,
        sortOverrides: Map<MessageReference, MessageSortOverride> = emptyMap(),
    ) = MessageListConfig(
        search = search,
        showingThreadedList = false,
        sortType = sortType,
        sortAscending = false,
        sortDateAscending = false,
        activeMessage = activeMessage,
        sortOverrides = sortOverrides,
    )

    private fun reference(uniqueId: Long) = MessageReference(ACCOUNT_ID, FOLDER_ID, "uid$uniqueId")

    private fun item(uniqueId: Long) = MessageListItem(
        account = mock<LegacyAccount> { on { id } doReturn ACCOUNT_ID },
        subject = "Subject $uniqueId",
        threadCount = 0,
        messageDate = 0L,
        internalDate = 0L,
        displayName = "Sender $uniqueId",
        displayAddress = null,
        displayMessageDateTime = "",
        previewText = "",
        isMessageEncrypted = false,
        isRead = false,
        isStarred = false,
        isAnswered = false,
        isForwarded = false,
        hasAttachments = false,
        uniqueId = uniqueId,
        folderId = FOLDER_ID,
        messageUid = "uid$uniqueId",
        databaseId = uniqueId,
        threadRoot = uniqueId,
        contactColor = 0,
        classification = MessageClass.HUMAN,
        isSenderAuthenticated = false,
    )

    private companion object {
        val ACCOUNT_ID = AccountIdFactory.create()
        const val FOLDER_ID = 1L
        val SEARCH = LocalMessageSearch()
    }
}
