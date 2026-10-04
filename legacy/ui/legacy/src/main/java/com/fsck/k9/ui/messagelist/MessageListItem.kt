package com.fsck.k9.ui.messagelist

import androidx.annotation.ColorInt
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mail.Address
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import com.fsck.k9.mail.MessageImportance

data class MessageListItem(
    val account: LegacyAccount,
    val subject: String?,
    val threadCount: Int,
    val messageDate: Long,
    val internalDate: Long,
    val displayName: CharSequence,
    val displayAddress: Address?,
    val displayMessageDateTime: String,
    val previewText: String,
    val isMessageEncrypted: Boolean,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isAnswered: Boolean,
    val isForwarded: Boolean,
    val hasAttachments: Boolean,
    val uniqueId: Long,
    val folderId: Long,
    val messageUid: String,
    val databaseId: Long,
    val threadRoot: Long,
    @get:ColorInt
    val contactColor: Int,
    val classification: MessageClass,
    val isSenderAuthenticated: Boolean,
    val importance: MessageImportance = MessageImportance.NORMAL,
    /**
     * The categories the server keeps on the message, such as the ones assigned in Outlook. Not the
     * [classification], which the app works out for itself.
     */
    val serverCategories: List<String> = emptyList(),
    /**
     * Whether the sender looks like someone they are not; see [net.thunderbird.feature.impersonation.Impersonation].
     */
    val isPossibleImpersonation: Boolean = false,
) {
    val messageReference: MessageReference
        get() = MessageReference(account.uuid, folderId, messageUid)
}
