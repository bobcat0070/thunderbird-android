package app.k9mail.legacy.mailstore

import app.k9mail.legacy.message.extractors.PreviewResult
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import net.thunderbird.feature.mail.message.classification.api.MessageClassification

data class SaveMessageData(
    val message: Message,
    val subject: String?,
    val date: Long,
    val internalDate: Long,
    val downloadState: MessageDownloadState,
    val attachmentCount: Int,
    val previewResult: PreviewResult,
    val textForSearchIndex: String? = null,
    val encryptionType: String?,

    /**
     * What kind of mail this is, decided from the headers while they are in hand.
     */
    val classification: MessageClassification = MessageClassification.UNKNOWN,
    /**
     * Whether the receiving server reported that the message passed DMARC. Recorded per message rather than
     * per sender, because the whole point is to tell a domain's real mail from mail claiming to be it.
     */
    val isSenderAuthenticated: Boolean = false,
)
