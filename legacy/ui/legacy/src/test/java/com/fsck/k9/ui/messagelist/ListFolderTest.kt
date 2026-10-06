package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class MessageListFolderNamesTest {

    @Test
    fun `messages from different folders should be named by their folder`() {
        val testSubject = listOf(item(1, INBOX_ID), item(2, MISC_ID))

        val result = testSubject.withFolderNames { _, folderId -> FOLDER_NAMES[folderId] }

        assertThat(result.map { it.folderName }).containsExactly("Inbox", "Misc")
    }

    @Test
    fun `a list from one folder should not repeat its name on every row`() {
        val testSubject = listOf(item(1, INBOX_ID), item(2, INBOX_ID))

        val result = testSubject.withFolderNames { _, folderId -> FOLDER_NAMES[folderId] }

        assertThat(result.map { it.folderName }).containsExactly(null, null)
    }

    @Test
    fun `folders with the same name in different accounts should not be named`() {
        // A unified inbox: every row would say "Inbox".
        val otherAccount = account("other")
        val testSubject = listOf(item(1, INBOX_ID), item(2, INBOX_ID, otherAccount))

        val result = testSubject.withFolderNames { _, _ -> "Inbox" }

        assertThat(result.map { it.folderName }).containsExactly(null, null)
    }

    @Test
    fun `each folder should be looked up only once`() {
        var lookups = 0
        val testSubject = listOf(item(1, INBOX_ID), item(2, MISC_ID), item(3, MISC_ID))

        testSubject.withFolderNames { _, folderId ->
            lookups++
            FOLDER_NAMES[folderId]
        }

        assertThat(lookups).isEqualTo(2)
    }

    private fun account(uuid: String) = mock<LegacyAccount> { on { this.uuid } doReturn uuid }

    private fun item(uniqueId: Long, folderId: Long, account: LegacyAccount = ACCOUNT) = MessageListItem(
        account = account,
        subject = "Subject $uniqueId",
        threadCount = 0,
        messageDate = 0L,
        internalDate = 0L,
        displayName = "Sender",
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
        folderId = folderId,
        messageUid = "uid$uniqueId",
        databaseId = uniqueId,
        threadRoot = uniqueId,
        contactColor = 0,
        classification = MessageClass.HUMAN,
        isSenderAuthenticated = false,
    )

    private companion object {
        const val INBOX_ID = 1L
        const val MISC_ID = 2L
        val FOLDER_NAMES = mapOf(INBOX_ID to "Inbox", MISC_ID to "Misc")
        val ACCOUNT: LegacyAccount = mock { on { uuid } doReturn "account" }
    }
}
