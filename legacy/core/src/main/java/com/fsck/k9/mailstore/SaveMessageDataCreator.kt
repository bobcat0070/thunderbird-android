package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.SaveMessageData
import com.fsck.k9.crypto.EncryptionExtractor
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.message.extractors.AttachmentCounter
import com.fsck.k9.message.extractors.MessageFulltextCreator
import com.fsck.k9.message.extractors.MessagePreviewCreator
import net.thunderbird.feature.mail.message.classification.api.MessageClassifier

@Suppress("LongParameterList")
class SaveMessageDataCreator(
    private val encryptionExtractor: EncryptionExtractor,
    private val messagePreviewCreator: MessagePreviewCreator,
    private val messageFulltextCreator: MessageFulltextCreator,
    private val attachmentCounter: AttachmentCounter,
    private val messageClassifier: MessageClassifier,
    private val knownContacts: KnownContacts,
    private val knownCorrespondents: KnownCorrespondents,
    private val authenticationServerTrust: AuthenticationServerTrust,
) {
    /**
     * @param accountUuid the account a message arriving from its server belongs to, whose server is the one whose
     *   verdict on the sender counts. `null` for a message this app wrote itself, which no server has checked.
     */
    @JvmOverloads
    fun createSaveMessageData(
        message: Message,
        downloadState: MessageDownloadState,
        subject: String? = null,
        accountUuid: String? = null,
    ): SaveMessageData {
        val now = System.currentTimeMillis()
        val date = message.sentDate?.time ?: now
        val internalDate = message.internalDate?.time ?: now
        val displaySubject = subject ?: message.subject

        // Classified here because this is the only point where the headers are in hand; the store keeps only
        // a subset of them, and re-deriving later would mean re-parsing the message.
        val senderAddress = message.from?.firstOrNull()?.address?.lowercase()
        val classification = messageClassifier.classify(
            message.toClassificationEvidence(
                isKnownContact = senderAddress?.let { knownContacts.isKnown(it) } == true,
                hasCorresponded = senderAddress?.let { knownCorrespondents.isKnown(it) } == true,
            ),
        )
        val authenticationResults = message.getHeader(authenticationResultsHeaderName()).orEmpty().toList()
        val trustedServerId = accountUuid?.let { uuid ->
            authenticationServerTrust.observe(uuid, authenticationResults)
            authenticationServerTrust.trustedServerId(uuid)
        }
        val isSenderAuthenticated = hasDmarcPass(authenticationResults, senderDomainOf(senderAddress), trustedServerId)

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
