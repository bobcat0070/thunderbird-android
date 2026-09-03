package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.util.Calendar
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import org.junit.Test
import org.mockito.kotlin.mock

class MessageListGroupingTest {

    @Test
    fun `a list with no bulk mail should keep every message`() {
        val items = listOf(item(1, MessageClass.HUMAN), item(2, MessageClass.UNKNOWN))

        val result = items.grouped()

        assertThat(result.messageIds()).containsExactly(1L, 2L)
    }

    @Test
    fun `unknown mail should stay in the list`() {
        // The classifier saying "I don't know" must not read as "this is bulk"; hiding it is how a real
        // message gets lost.
        val items = listOf(item(1, MessageClass.UNKNOWN))

        val result = items.grouped()

        assertThat(result.messageIds()).containsExactly(1L)
        assertThat(result.bundles()).isEmpty()
    }

    @Test
    fun `category rows should be pinned above every message`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.HUMAN),
            item(4, MessageClass.NOTIFICATION),
        )

        val result = items.grouped()

        assertThat(result.bundles().map { it.messageClass })
            .containsExactly(MessageClass.NOTIFICATION, MessageClass.NEWSLETTER)
        assertThat(result.messageIds()).containsExactly(1L, 3L)
    }

    @Test
    fun `bulk mail should be lifted out of the list entirely`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.NOTIFICATION),
        )

        val result = items.grouped()

        assertThat(result.messageIds()).isEmpty()
        assertThat(result.bundles().map { it.messageCount }).containsExactly(1, 2)
    }

    @Test
    fun `turning grouping off should put every message back in the list`() {
        // What someone wants when they are hunting for one message rather than triaging.
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NEWSLETTER),
            item(3, MessageClass.NOTIFICATION),
        )

        val result = items.toViewItems(isGroupingEnabled = false, showDayHeaders = false)

        assertThat(result.bundles()).isEmpty()
        assertThat(result.messageIds()).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `the toggle should be the first row either way`() {
        val items = listOf(item(1, MessageClass.NEWSLETTER))

        for (enabled in listOf(true, false)) {
            val result = items.toViewItems(isGroupingEnabled = enabled, showDayHeaders = false)

            assertThat(result.first() is MessageListViewItem.GroupingToggle).isTrue()
            assertThat((result.first() as MessageListViewItem.GroupingToggle).isEnabled).isEqualTo(enabled)
        }
    }

    @Test
    fun `a day header should be emitted for the first message`() {
        // So the top of the list says which day it is looking at rather than leaving it implied.
        val items = listOf(item(1, MessageClass.HUMAN, at = daysAgo(0)))

        val result = items.toViewItems(isGroupingEnabled = true, showDayHeaders = true, now = NOW)

        assertThat(result.dayHeaders().map { it.relativeDay }).containsExactly(RelativeDay.TODAY)
    }

    @Test
    fun `a day header should be emitted only when the day changes`() {
        val items = listOf(
            item(1, MessageClass.HUMAN, at = daysAgo(0)),
            item(2, MessageClass.HUMAN, at = daysAgo(0)),
            item(3, MessageClass.HUMAN, at = daysAgo(1)),
            item(4, MessageClass.HUMAN, at = daysAgo(5)),
        )

        val result = items.toViewItems(isGroupingEnabled = true, showDayHeaders = true, now = NOW)

        assertThat(result.dayHeaders().map { it.relativeDay })
            .containsExactly(RelativeDay.TODAY, RelativeDay.YESTERDAY, RelativeDay.EARLIER)
    }

    @Test
    fun `messages on the same day should keep their order under one header`() {
        val items = listOf(
            item(1, MessageClass.HUMAN, at = daysAgo(0)),
            item(2, MessageClass.HUMAN, at = daysAgo(0)),
        )

        val result = items.toViewItems(isGroupingEnabled = true, showDayHeaders = true, now = NOW)

        assertThat(result.dayHeaders()).hasSize(1)
        assertThat(result.messageIds()).containsExactly(1L, 2L)
    }

    @Test
    fun `bulk mail should not create a day header of its own`() {
        // It has been lifted into a category row, so the day it arrived is no longer part of the timeline.
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, at = daysAgo(0)),
            item(2, MessageClass.HUMAN, at = daysAgo(3)),
        )

        val result = items.toViewItems(isGroupingEnabled = true, showDayHeaders = true, now = NOW)

        assertThat(result.dayHeaders().map { it.relativeDay }).containsExactly(RelativeDay.EARLIER)
    }

    @Test
    fun `day headers should be omitted when not asked for`() {
        val items = listOf(item(1, MessageClass.HUMAN, at = daysAgo(0)))

        val result = items.toViewItems(isGroupingEnabled = true, showDayHeaders = false)

        assertThat(result.dayHeaders()).isEmpty()
    }

    @Test
    fun `every row should have a distinct id`() {
        // They all feed the adapter's stable ids; a collision makes rows swap places on every update.
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, at = daysAgo(0)),
            item(2, MessageClass.NOTIFICATION, at = daysAgo(0)),
            item(3, MessageClass.HUMAN, at = daysAgo(0)),
            item(4, MessageClass.HUMAN, at = daysAgo(1)),
        )

        val result = items.toViewItems(isGroupingEnabled = true, showDayHeaders = true, now = NOW)

        assertThat(result.map { it.viewId }.distinct()).hasSize(result.size)
    }

    @Test
    fun `a category row should count its unread messages separately`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, isRead = false),
            item(2, MessageClass.NEWSLETTER, isRead = true),
        )

        val bundle = items.grouped().bundles().single()

        assertThat(bundle.messageCount).isEqualTo(2)
        assertThat(bundle.unreadCount).isEqualTo(1)
    }

    @Test
    fun `a category row should name distinct senders only`() {
        val items = listOf(
            item(1, MessageClass.NEWSLETTER, displayName = "LinkedIn"),
            item(2, MessageClass.NEWSLETTER, displayName = "LinkedIn"),
            item(3, MessageClass.NEWSLETTER, displayName = "Kohl's"),
        )

        assertThat(items.grouped().bundles().single().senderNames).containsExactly("LinkedIn", "Kohl's")
    }

    private fun List<MessageListItem>.grouped() =
        toViewItems(isGroupingEnabled = true, showDayHeaders = false)

    private fun List<MessageListViewItem>.messageIds() =
        filterIsInstance<MessageListViewItem.Message>().map { it.viewId }

    private fun List<MessageListViewItem>.bundles() = filterIsInstance<MessageListViewItem.Bundle>()

    private fun List<MessageListViewItem>.dayHeaders() = filterIsInstance<MessageListViewItem.DayHeader>()

    private fun daysAgo(days: Int): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = NOW }
        calendar.add(Calendar.DAY_OF_YEAR, -days)

        return calendar.timeInMillis
    }

    private fun item(
        uniqueId: Long,
        classification: MessageClass,
        isRead: Boolean = false,
        displayName: String = "Sender $uniqueId",
        at: Long = NOW,
    ) = MessageListItem(
        account = mock(),
        subject = "Subject $uniqueId",
        threadCount = 0,
        messageDate = at,
        internalDate = at,
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

    private companion object {
        /**
         * Midday, so adding or subtracting days never crosses a boundary by accident.
         */
        val NOW: Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
