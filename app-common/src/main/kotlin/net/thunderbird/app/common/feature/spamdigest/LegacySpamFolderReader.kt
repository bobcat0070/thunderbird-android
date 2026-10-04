package net.thunderbird.app.common.feature.spamdigest

import app.k9mail.legacy.mailstore.MessageStore
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.AuthenticationMethod
import com.fsck.k9.mailstore.AuthenticationServerTrust
import com.fsck.k9.mailstore.authenticationOutcomes
import com.fsck.k9.mailstore.authenticationResultsHeaderName
import com.fsck.k9.mailstore.senderDomainOf
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.spamdigest.SenderCheck
import net.thunderbird.feature.spamdigest.SenderCheckMethod
import net.thunderbird.feature.spamdigest.SpamFolderContents
import net.thunderbird.feature.spamdigest.SpamFolderReader
import net.thunderbird.feature.spamdigest.SpamMessage

/**
 * Reads a spam folder from the message store, after a sync of that one folder.
 *
 * Sender checks are worked out by the same rules as the message view: only the topmost `Authentication-Results`
 * header, and only when the account's own server wrote it, so a spammer's forged header earns no ticks here
 * either.
 */
internal class LegacySpamFolderReader(
    private val accountManager: LegacyAccountDtoManager,
    private val messagingController: MessagingController,
    private val messageStoreManager: MessageStoreManager,
    private val authenticationServerTrust: AuthenticationServerTrust,
) : SpamFolderReader {

    override suspend fun read(accountId: String, from: Instant, until: Instant): SpamFolderContents? =
        withContext(Dispatchers.IO) {
            val account = accountManager.getAccount(accountId) ?: return@withContext null
            val folderId = account.spamFolderId ?: return@withContext null
            val messageStore = messageStoreManager.getMessageStore(account)

            val isRefreshed = refresh(account, folderId, messageStore)
            val trustedServerId = authenticationServerTrust.trustedServerId(accountId)

            val storedMessages = messageStore.getMessages(
                selection = "messages.folder_id = ? AND messages.internal_date >= ? AND messages.internal_date < ?",
                selectionArgs = arrayOf(
                    folderId.toString(),
                    from.toEpochMilliseconds().toString(),
                    until.toEpochMilliseconds().toString(),
                ),
                sortOrder = "messages.internal_date ASC",
                messageMapper = { message ->
                    StoredSpam(
                        serverId = message.messageServerId,
                        sender = message.fromAddresses.firstOrNull(),
                        subject = message.subject,
                        internalDate = message.internalDate,
                    )
                },
            )

            SpamFolderContents(
                messages = storedMessages.map { stored ->
                    stored.toSpamMessage(senderChecks(messageStore, folderId, stored, trustedServerId))
                },
                isRefreshed = isRefreshed,
            )
        }

    /**
     * Syncs the folder, which a spam folder usually is not in the background.
     *
     * @return whether the sync got through, judged by the folder's last-checked time moving on: a failed sync
     *   leaves it as it was.
     */
    private fun refresh(account: LegacyAccountDto, folderId: Long, messageStore: MessageStore): Boolean {
        val folderServerId = messageStore.getFolderServerId(folderId) ?: return false
        val lastCheckedBefore = messageStore.getFolder(folderId) { it.lastChecked }

        messagingController.synchronizeMailboxBlocking(account, folderServerId)

        val lastCheckedAfter = messageStore.getFolder(folderId) { it.lastChecked }
        return lastCheckedAfter != null && lastCheckedAfter != lastCheckedBefore
    }

    private fun senderChecks(
        messageStore: MessageStore,
        folderId: Long,
        stored: StoredSpam,
        trustedServerId: String?,
    ): List<SenderCheck> {
        val headerName = authenticationResultsHeaderName()
        val headerValues = messageStore.getHeaders(folderId, stored.serverId, setOf(headerName))
            .filter { it.name.equals(headerName, ignoreCase = true) }
            .map { it.value }

        return authenticationOutcomes(headerValues, senderDomainOf(stored.sender?.address), trustedServerId)
            .map { outcome -> SenderCheck(outcome.method.toSenderCheckMethod(), outcome.passed) }
    }

    private fun AuthenticationMethod.toSenderCheckMethod(): SenderCheckMethod = when (this) {
        AuthenticationMethod.SPF -> SenderCheckMethod.SPF
        AuthenticationMethod.DKIM -> SenderCheckMethod.DKIM
        AuthenticationMethod.DMARC -> SenderCheckMethod.DMARC
    }

    private class StoredSpam(
        val serverId: String,
        val sender: Address?,
        val subject: String?,
        val internalDate: Long,
    ) {
        fun toSpamMessage(senderChecks: List<SenderCheck>) = SpamMessage(
            senderName = sender?.personal,
            senderAddress = sender?.address,
            subject = subject,
            receivedAt = Instant.fromEpochMilliseconds(internalDate),
            senderChecks = senderChecks,
        )
    }
}
