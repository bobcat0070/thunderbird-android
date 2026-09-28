package app.k9mail.feature.widget.message.list

import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import app.k9mail.legacy.message.controller.MessageReference
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import org.junit.Test

class LastActionIconTest {

    @Test
    fun `a message replied to should show the reply arrow`() {
        assertThat(item(isAnswered = true, isForwarded = false).lastActionIcon()).isEqualTo(Icons.Outlined.Reply)
    }

    @Test
    fun `a message forwarded should show the forward arrow`() {
        assertThat(item(isAnswered = false, isForwarded = true).lastActionIcon()).isEqualTo(Icons.Outlined.Forward)
    }

    @Test
    fun `a message replied to and forwarded should show both arrows, as the message list does`() {
        assertThat(item(isAnswered = true, isForwarded = true).lastActionIcon())
            .isEqualTo(Icons.Outlined.CompareArrows)
    }

    @Test
    fun `a message neither replied to nor forwarded should show no arrow`() {
        assertThat(item(isAnswered = false, isForwarded = false).lastActionIcon()).isNull()
    }

    private fun item(isAnswered: Boolean, isForwarded: Boolean) = MessageListItem(
        displayName = "Sender",
        displayAddress = null,
        isSenderAuthenticated = false,
        displayDate = "",
        subject = "Subject",
        preview = "",
        isRead = false,
        isAnswered = isAnswered,
        isForwarded = isForwarded,
        hasAttachments = false,
        threadCount = 0,
        threadRoot = 0,
        accountColor = 0,
        messageReference = MessageReference("account", 1L, "uid"),
        uniqueId = 1L,
        classification = MessageClass.HUMAN,
        sortSubject = null,
        sortMessageDate = 0L,
        sortInternalDate = 0L,
        sortIsStarred = false,
        sortDatabaseId = 1L,
    )
}
