package net.thunderbird.app.common.feature.spamdigest

import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.mail.Message
import com.fsck.k9.mailstore.authenticationResultsHeaderName
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.spamdigest.SpamArrivalListener

private const val LOG_TAG = "SpamAlert"

/**
 * Passes each new message a sync brings into an account's spam folder on to the spam alert.
 *
 * Runs on the sync thread, once per new unread message, so it only looks the message up in what is already stored.
 */
internal class SpamArrivalMessagingListener(
    private val messageStoreManager: MessageStoreManager,
    private val spamSenderAssessor: SpamSenderAssessor,
    private val spamArrivalListener: SpamArrivalListener,
    private val clock: Clock,
    private val logger: Logger,
) : SimpleMessagingListener() {

    override fun synchronizeMailboxNewMessage(account: LegacyAccountDto, folderServerId: String, message: Message) {
        val spamFolderId = account.spamFolderId ?: return
        val messageStore = messageStoreManager.getMessageStore(account)
        if (messageStore.getFolderId(folderServerId) != spamFolderId) return

        try {
            val headerName = authenticationResultsHeaderName()
            val authenticationResults = messageStore.getHeaders(spamFolderId, message.uid, setOf(headerName))
                .filter { it.name.equals(headerName, ignoreCase = true) }
                .map { it.value }
            val receivedAt = (message.internalDate ?: message.sentDate)
                ?.let { Instant.fromEpochMilliseconds(it.time) }
                ?: clock.now()

            val spamMessage = spamSenderAssessor.assess(
                accountId = account.uuid,
                sender = message.from?.firstOrNull(),
                subject = message.subject,
                receivedAt = receivedAt,
                authenticationResults = authenticationResults,
            )

            spamArrivalListener.onSpamArrived(account.uuid, spamFolderId, message.uid, spamMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // An alert that cannot be worked out must never fail the sync it rides on.
            logger.warn(LOG_TAG) { "Could not check spam for an alert: ${e::class.simpleName}" }
        }
    }
}
