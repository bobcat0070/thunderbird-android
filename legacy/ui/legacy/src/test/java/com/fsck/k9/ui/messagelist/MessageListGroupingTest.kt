package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import org.junit.Test
import org.mockito.kotlin.mock

class MessageListGroupingTest {

    @Test
    fun `a list with no bulk mail should be unchanged`() {
        val items = listOf(item(1, MessageClass.HUMAN), item(2, MessageClass.UNKNOWN))

        val result = items.groupByClassification()

        assertThat(result.map { it.viewId }).containsExactly(1L, 2L)
    }

    @Test
    fun `unknown mail should stay in the list`() {
        // The classifier saying "I don't know" must not read as "this is bulk"; hiding it is how a real
        // message gets lost.
        val items = listOf(item(1, MessageClass.UNKNOWN))

        val result = items.groupByClassification()

        assertThat(result).hasSize(1)
        assertThat(result.first() is MessageListViewItem.Message).isTrue()
    }

    @Test
    fun `bundles should be pinned above every message`() {
        // The bundle stands for a whole category rather than for one moment in the timeline, so it does not
        // move as mail arrives.
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.HUMAN),
            item(4, MessageClass.NOTIFICATION),
        )

        val result = items.groupByClassification()

        assertThat(result.map { it.viewId }).containsExactly(
            bundleId(MessageClass.NOTIFICATION),
            bundleId(MessageClass.NEWSLETTER),
            1L,
            3L,
        )
    }

    @Test
    fun `bulk mail should be lifted out of the list entirely`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.NOTIFICATION),
        )

        val result = items.groupByClassification()

        assertThat(result).hasSize(2)
        assertThat(result.filterIsInstance<MessageListViewItem.Bundle>().map { it.messageCount })
            .containsExactly(1, 2)
    }

    @Test
    fun `a class with no messages should get no row`() {
        val items = listOf(item(1, MessageClass.NEWSLETTER))

        val result = items.groupByClassification()

        assertThat(result.filterIsInstance<MessageListViewItem.Bundle>().map { it.messageClass })
            .containsExactly(MessageClass.NEWSLETTER)
    }

    @Test
    fun `remaining messages should keep their order`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.UNKNOWN),
            item(4, MessageClass.HUMAN),
        )

        val result = items.groupByClassification()

        assertThat(result.filterIsInstance<MessageListViewItem.Message>().map { it.viewId })
            .containsExactly(1L, 3L, 4L)
    }

    @Test
    fun `a bundle should count its unread messages separately`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, isRead = false),
            item(2, MessageClass.NEWSLETTER, isRead = true),
        )

        val bundle = items.groupByClassification()
            .filterIsInstance<MessageListViewItem.Bundle>()
            .single()

        assertThat(bundle.messageCount).isEqualTo(2)
        assertThat(bundle.unreadCount).isEqualTo(1)
    }

    @Test
    fun `a bundle should name distinct senders only`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, displayName = "LinkedIn"),
            item(2, MessageClass.NEWSLETTER, displayName = "LinkedIn"),
            item(3, MessageClass.NEWSLETTER, displayName = "Kohl's"),
        )

        val bundle = items.groupByClassification()
            .filterIsInstance<MessageListViewItem.Bundle>()
            .single()

        assertThat(bundle.senderNames).containsExactly("LinkedIn", "Kohl's")
    }

    @Test
    fun `a bundle id should not collide with a message id`() {
        // Both feed the adapter's stable ids; a collision would make rows swap places on every update.
        val items = listOf(item(1, MessageClass.NEWSLETTER), item(2, MessageClass.NOTIFICATION))

        val result = items.groupByClassification()

        assertThat(result.map { it.viewId }.distinct()).hasSize(2)
        assertThat(result.all { it.viewId < 0 }).isTrue()
    }

    private fun bundleId(messageClass: MessageClass) = -(messageClass.ordinal + 2L)

    private fun item(
        uniqueId: Long,
        classification: MessageClass,
        isRead: Boolean = false,
        displayName: String = "Sender $uniqueId",
    ) = MessageListItem(
        account = mock(),
        subject = "Subject $uniqueId",
        threadCount = 0,
        messageDate = uniqueId,
        internalDate = uniqueId,
        displayName = displayName,
        displayAddress = null,
        displayMessageDateTime = "",
        previewText = "",
        isMessageEncrypted = false,
        isRead = isRead,
        isStarred = false,
        isAnswered = false,
        isForwarded = false,
        hasAttachments = false,
        uniqueId = uniqueId,
        folderId = 1L,
        messageUid = "uid$uniqueId",
        databaseId = uniqueId,
        threadRoot = uniqueId,
        contactColor = 0,
        classification = classification,
        isSenderAuthenticated = false,
    )
}
