package app.k9mail.feature.widget.message.list

import androidx.annotation.DrawableRes
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mail.Address
import net.thunderbird.feature.mail.message.classification.api.MessageClass

internal data class MessageListItem(
    val displayName: String,
    val displayAddress: Address?,
    val isSenderAuthenticated: Boolean,
    val displayDate: String,
    val subject: String,
    val preview: String,
    val isRead: Boolean,
    val isAnswered: Boolean,
    val isForwarded: Boolean,
    val hasAttachments: Boolean,
    val threadCount: Int,
    /**
     * The conversation this message is part of, opened in place of the message when [threadCount] is above one.
     */
    val threadRoot: Long,
    val accountColor: Int,
    val messageReference: MessageReference,
    val uniqueId: Long,
    val classification: MessageClass,

    val sortSubject: String?,
    val sortMessageDate: Long,
    val sortInternalDate: Long,
    val sortIsStarred: Boolean,
    val sortDatabaseId: Long,
)

/**
 * The arrow the message list shows for what was done with a message - replied to, forwarded, or both - or `null`
 * for neither.
 */
@DrawableRes
internal fun MessageListItem.lastActionIcon(): Int? = when {
    isAnswered && isForwarded -> Icons.Outlined.CompareArrows
    isAnswered -> Icons.Outlined.Reply
    isForwarded -> Icons.Outlined.Forward
    else -> null
}
