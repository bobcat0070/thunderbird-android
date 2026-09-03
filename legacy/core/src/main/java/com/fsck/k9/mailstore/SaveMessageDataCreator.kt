package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.SaveMessageData
import com.fsck.k9.crypto.EncryptionExtractor
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.message.extractors.AttachmentCounter
import com.fsck.k9.message.extractors.MessageFulltextCreator
import com.fsck.k9.message.extractors.MessagePreviewCreator
import net.thunderbird.feature.mail.message.classification.api.MessageClassifier

class SaveMessageDataCreator(
    private val encryptionExtractor: EncryptionExtractor,
    private val messagePreviewCreator: MessagePreviewCreator,
    private val messageFulltextCreator: MessageFulltextCreator,
    private val attachmentCounter: AttachmentCounter,
    private val messageClassifier: MessageClassifier,
) {
    fun createSaveMessageData(
        message: Message,
        downloadState: MessageDownloadState,
        subject: String? = null,
    ): SaveMessageData {
        val now = System.currentTimeMillis()
        val date = message.sentDate?.time ?: now
        val internalDate = message.internalDate?.time ?: now
        val displaySubject = subject ?: message.subject

        // Classified here because this is the only point where the headers are in hand; the store keeps only
        // a subset of them, and re-deriving later would mean re-parsing the message.
        val classification = messageClassifier.classify(message.toClassificationEvidence())
        val isSenderAuthenticated = hasDmarcPass(
            message.getHeader(authenticationResultsHeaderName()).orEmpty().toList(),
        )

        val encryptionResult = encryptionExtractor.extractEncryption(message)
        return if (encryptionResult != null) {
            SaveMessageData(
                message = message,
                subject = displaySubject,
                date = date,
                internalDate = internalDate,
                downloadState = downloadState,
                attachmentCount = encryptionResult.attachmentCount,
                previewResult = encryptionResult.previewResult,
                textForSearchIndex = encryptionResult.textForSearchIndex,
                encryptionType = encryptionResult.encryptionType,
                classification = classification,
                isSenderAuthenticated = isSenderAuthenticated,
            )
        } else {
            SaveMessageData(
                message = message,
                subject = displaySubject,
                date = date,
                internalDate = internalDate,
                downloadState = downloadState,
                attachmentCount = attachmentCounter.getAttachmentCount(message),
                previewResult = messagePreviewCreator.createPreview(message),
                textForSearchIndex = messageFulltextCreator.createFulltext(message),
                encryptionType = null,
                classification = classification,
                isSenderAuthenticated = isSenderAuthenticated,
            )
        }
    }
}
