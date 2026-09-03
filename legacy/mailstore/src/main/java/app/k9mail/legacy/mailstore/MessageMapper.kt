package app.k9mail.legacy.mailstore

import app.k9mail.legacy.message.extractors.PreviewResult
import com.fsck.k9.mail.Address
import net.thunderbird.feature.mail.message.classification.api.MessageClass

fun interface MessageMapper<T> {
    fun map(message: MessageDetailsAccessor): T
}

interface MessageDetailsAccessor {
    val id: Long
    val messageServerId: String
    val folderId: Long
    val fromAddresses: List<Address>
    val toAddresses: List<Address>
    val ccAddresses: List<Address>
    val messageDate: Long
    val internalDate: Long
    val subject: String?
    val preview: PreviewResult
    val isRead: Boolean
    val isStarred: Boolean
    val isAnswered: Boolean
    val isForwarded: Boolean
    val hasAttachments: Boolean
    val threadRoot: Long
    val threadCount: Int

    /**
     * What kind of mail this is, for grouping the list.
     *
     * [MessageClass.UNKNOWN] both for mail that carried no evidence and for mail stored before the app
     * classified anything, since neither can be presented as a confident answer.
     */
    val classification: MessageClass

    /**
     * Whether the receiving server reported that this message passed DMARC.
     *
     * Per message rather than per sender on purpose: the point of the flag is to tell a domain's real mail
     * apart from mail that only claims to be from it.
     */
    val isSenderAuthenticated: Boolean
}
