package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.message.controller.MessageReference
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.mail.message.classification.api.MessageClass
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
    }
}
