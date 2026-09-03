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

        val result = items.groupByClassification(expandedClasses = emptySet())

        assertThat(result.map { it.viewId }).containsExactly(1L, 2L)
    }

    @Test
    fun `unknown mail should stay in the list`() {
        // The classifier saying "I don't know" must not read as "this is bulk"; hiding it is how a real
        // message gets lost.
        val items = listOf(item(1, MessageClass.UNKNOWN))

        val result = items.groupByClassification(expandedClasses = emptySet())

        assertThat(result).hasSize(1)
        assertThat(result.first() is MessageListViewItem.Message).isTrue()
    }

    @Test
    fun `bulk mail should collapse into one row per class`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.NOTIFICATION),
        )

        val result = items.groupByClassification(expandedClasses = emptySet())

        assertThat(result).hasSize(2)
        assertThat(result.filterIsInstance<MessageListViewItem.Bundle>().map { it.messageCount })
            .containsExactly(2, 1)
    }

    @Test
    fun `a bundle should sit where its newest message was`() {
        // The list is already in date order, so keeping the bundle at its first member's position is what
        // stops a burst of newsletters from pushing correspondence down the screen.
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.HUMAN),
            item(4, MessageClass.NEWSLETTER),
        )

        val result = items.groupByClassification(expandedClasses = emptySet())

        assertThat(result.map { it.viewId }).containsExactly(1L, bundleId(MessageClass.NEWSLETTER), 3L)
    }

    @Test
    fun `an expanded bundle should list its messages after the bundle row`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.NEWSLETTER),
        )

        val result = items.groupByClassification(expandedClasses = setOf(MessageClass.NEWSLETTER))

        assertThat(result.map { it.viewId })
            .containsExactly(1L, bundleId(MessageClass.NEWSLETTER), 2L, 3L)
    }

    @Test
    fun `expanding one bundle should leave the other collapsed`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER),
            item(2, MessageClass.NOTIFICATION),
            item(3, MessageClass.NOTIFICATION),
        )

        val result = items.groupByClassification(expandedClasses = setOf(MessageClass.NEWSLETTER))

        assertThat(result.map { it.viewId }).containsExactly(
            bundleId(MessageClass.NEWSLETTER),
            1L,
            bundleId(MessageClass.NOTIFICATION),
        )
    }

    @Test
    fun `a bundle should count only its unread messages separately`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, isRead = false),
            item(2, MessageClass.NEWSLETTER, isRead = true),
        )

        val bundle = items.groupByClassification(emptySet())
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

        val bundle = items.groupByClassification(emptySet())
            .filterIsInstance<MessageListViewItem.Bundle>()
            .single()

        assertThat(bundle.senderNames).containsExactly("LinkedIn", "Kohl's")
    }

    @Test
    fun `a bundle id should not collide with a message id`() {
        // Both feed the adapter's stable ids; a collision would make rows swap places on every update.
        val items = listOf(item(1, MessageClass.NEWSLETTER), item(2, MessageClass.NOTIFICATION))

        val result = items.groupByClassification(emptySet())

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
    )
}
