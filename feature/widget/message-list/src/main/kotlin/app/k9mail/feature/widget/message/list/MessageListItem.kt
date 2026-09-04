package app.k9mail.feature.widget.message.list

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.feature.mail.message.classification.api.MessageClass

internal data class MessageListItem(
    val displayName: String,
    val displayDate: String,
    val subject: String,
    val preview: String,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val threadCount: Int,
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
