package net.thunderbird.app.common.feature.spamdigest

import app.k9mail.legacy.mailstore.MessageStore
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.authenticationResultsHeaderName
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.spamdigest.SpamFolderContents
import net.thunderbird.feature.spamdigest.SpamFolderReader

/**
 * Reads a spam folder from the message store, after a sync of that one folder.
 *
 * What each message's sender is, and whether they can be trusted, is worked out by [SpamSenderAssessor].
 */
internal class LegacySpamFolderReader(
    private val accountManager: LegacyAccountDtoManager,
    private val messagingController: MessagingController,
    private val messageStoreManager: MessageStoreManager,
    private val spamSenderAssessor: SpamSenderAssessor,
) : SpamFolderReader {

    override suspend fun read(accountId: String, from: Instant, until: Instant): SpamFolderContents? =
        withContext(Dispatchers.IO) {
            val account = accountManager.getAccount(accountId) ?: return@withContext null
            val folderId = account.spamFolderId ?: return@withContext null
            val messageStore = messageStoreManager.getMessageStore(account)

            val isRefreshed = refresh(account, folderId, messageStore)

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
                    spamSenderAssessor.assess(
                        accountId = accountId,
                        sender = stored.sender,
                        subject = stored.subject,
                        receivedAt = Instant.fromEpochMilliseconds(stored.internalDate),
                        authenticationResults = authenticationResults(messageStore, folderId, stored.serverId),
                    )
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

    private fun authenticationResults(messageStore: MessageStore, folderId: Long, serverId: String): List<String> {
        val headerName = authenticationResultsHeaderName()

        return messageStore.getHeaders(folderId, serverId, setOf(headerName))
            .filter { it.name.equals(headerName, ignoreCase = true) }
            .map { it.value }
    }

    private class StoredSpam(
        val serverId: String,
        val sender: Address?,
        val subject: String?,
        val internalDate: Long,
    )
}
