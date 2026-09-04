package app.k9mail.feature.widget.message.list

import app.k9mail.legacy.message.controller.MessageReference
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import net.thunderbird.core.preference.widget.WidgetSettings
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import org.junit.Test

class WidgetCategoryFilterTest {

    @Test
    fun `everything enabled should keep every message`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NOTIFICATION),
            item(3, MessageClass.NEWSLETTER),
            item(4, MessageClass.UNKNOWN),
        )

        val result = items.filterByCategory(WidgetSettings())

        assertThat(result).hasSize(4)
    }

    @Test
    fun `personal only should drop bulk mail`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NOTIFICATION),
            item(3, MessageClass.NEWSLETTER),
        )

        val result = items.filterByCategory(personalOnly())

        assertThat(result.map { it.uniqueId }).containsExactly(1L)
    }

    @Test
    fun `unclassified mail should count as personal`() {
        // The widget is a glance at what might need the user. Burying a message the classifier could not
        // place is the failure that actually costs something.
        val items = listOf(item(1, MessageClass.UNKNOWN))

        val result = items.filterByCategory(personalOnly())

        assertThat(result.map { it.uniqueId }).containsExactly(1L)
    }

    @Test
    fun `notifications only should keep notifications and nothing else`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NOTIFICATION),
            item(3, MessageClass.NEWSLETTER),
            item(4, MessageClass.UNKNOWN),
        )

        val result = items.filterByCategory(
            WidgetSettings(showPersonal = false, showNotifications = true, showNewsletters = false),
        )

        assertThat(result.map { it.uniqueId }).containsExactly(2L)
    }

    @Test
    fun `two categories should both be kept`() {
        val items = listOf(
            item(1, MessageClass.HUMAN),
            item(2, MessageClass.NOTIFICATION),
            item(3, MessageClass.NEWSLETTER),
        )

        val result = items.filterByCategory(
            WidgetSettings(showPersonal = true, showNotifications = false, showNewsletters = true),
        )

        assertThat(result.map { it.uniqueId }).containsExactly(1L, 3L)
    }

    @Test
    fun `everything disabled should keep nothing`() {
        // An empty widget is the honest result of asking for no categories, not a reason to show everything.
        val items = listOf(item(1, MessageClass.HUMAN), item(2, MessageClass.NEWSLETTER))

        val result = items.filterByCategory(
            WidgetSettings(showPersonal = false, showNotifications = false, showNewsletters = false),
        )

        assertThat(result).hasSize(0)
    }

    @Test
    fun `filtering should not reorder what it keeps`() {
        val items = listOf(
            item(3, MessageClass.HUMAN),
            item(1, MessageClass.NEWSLETTER),
            item(2, MessageClass.HUMAN),
        )

        val result = items.filterByCategory(personalOnly())

        assertThat(result.map { it.uniqueId }).isEqualTo(listOf(3L, 2L))
    }

    private fun personalOnly() =
        WidgetSettings(showPersonal = true, showNotifications = false, showNewsletters = false)

    private fun item(uniqueId: Long, classification: MessageClass) = MessageListItem(
        displayName = "Sender $uniqueId",
        displayDate = "",
        subject = "Subject $uniqueId",
        preview = "",
        isRead = false,
        hasAttachments = false,
        threadCount = 0,
        accountColor = 0,
        messageReference = MessageReference("account", 1L, "uid$uniqueId"),
        uniqueId = uniqueId,
        classification = classification,
        sortSubject = null,
        sortMessageDate = uniqueId,
        sortInternalDate = uniqueId,
        sortIsStarred = false,
        sortDatabaseId = uniqueId,
    )
}
