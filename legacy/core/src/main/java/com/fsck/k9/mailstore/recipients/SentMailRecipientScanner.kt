package com.fsck.k9.mailstore.recipients

import app.k9mail.legacy.mailstore.MessageListRepository
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.logging.Logger

private const val TAG = "SentMailRecipientScanner"

/**
 * How long a scan is good for.
 *
 * Mail sent from this device is recorded as it is sent, so a scan only has to catch what was sent elsewhere -
 * from a desktop, or from the provider's own webmail - which arrives with the next sync of the sent folder.
 */
private const val SCAN_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L

/**
 * When an empty result is retried.
 *
 * Empty nearly always means the sent folder has not been synchronised yet rather than that the user has never
 * written to anyone, and waiting hours to look again would leave completion empty for exactly as long.
 */
private const val EMPTY_SCAN_INTERVAL_MILLIS = 5L * 60L * 1000L

/**
 * Where the last scan of an account's sent folder got to.
 */
private const val SCAN_WATERMARK_KEY = "sent_scan_highest_id"

/**
 * Builds the history of who the user writes to out of the mail they have already sent.
 *
 * Without this, completion would know nobody until the user sent their next message, and an account restored
 * onto a new device would start from nothing despite years of sent mail sitting in the store. Reading the sent
 * folders is what makes the feature useful on first run.
 *
 * Recording as mail is sent still earns its place: it is immediate, it works for an account whose sent folder
 * the server does not keep, and it does not depend on a sync having happened.
 */
class SentMailRecipientScanner(
    private val accountManager: LegacyAccountDtoManager,
    private val messageListRepository: MessageListRepository,
    private val index: RecipientIndex,
    private val logger: Logger? = null,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var lastScanAt = 0L
    private var lastScanFoundNothing = true

    /**
     * Scans the sent folders unless a recent scan already did.
     *
     * Safe to call often - from opening a compose screen, or after a sync - because the interval decides
     * whether anything is read.
     */
    fun scanIfDue() {
        synchronized(lock) {
            val interval = if (lastScanFoundNothing) EMPTY_SCAN_INTERVAL_MILLIS else SCAN_INTERVAL_MILLIS
            if (currentTimeMillis() - lastScanAt < interval) return

            lastScanAt = currentTimeMillis()
            lastScanFoundNothing = scan() == 0
        }
    }

    /**
     * @return how many messages were read.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun scan(): Int {
        var messages = 0

        for (account in accountManager.getAccounts()) {
            val sentFolderId = account.sentFolderId ?: continue

            // One unreadable account must not stop the others: the cost is that its history is missing from
            // completion until the next scan, never that everybody else's is.
            try {
                messages += scanAccount(account.uuid, sentFolderId)
            } catch (e: Exception) {
                logger?.debug(TAG, e) { "Could not scan sent mail for recipients" }
            }
        }

        return messages
    }

    /**
     * Reads only what a previous scan has not already counted.
     *
     * The count is a ranking signal, so counting the same message twice would quietly promote whoever happened
     * to be in the store when the app was last opened. Stored rows only ever gain higher ids, so the highest id
     * seen is a watermark that survives restarts.
     */
    private fun scanAccount(accountUuid: String, folderId: Long): Int {
        val lastScannedId = index.syncState(accountUuid, SCAN_WATERMARK_KEY)?.toLongOrNull() ?: 0L

        val sent = messageListRepository.getMessages(
            accountUuid = accountUuid,
            selection = "folder_id = ? AND id > ?",
            selectionArgs = arrayOf(folderId.toString(), lastScannedId.toString()),
            sortOrder = "id ASC",
            messageMapper = { message ->
                SentMessage(
                    id = message.id,
                    date = message.messageDate,
                    // An address named twice in one message is one message to that address, not two.
                    recipients = (message.toAddresses + message.ccAddresses)
                        .mapNotNull { address ->
                            address.address?.let { RemoteContact(it, address.personal?.takeIf(String::isNotBlank)) }
                        }
                        .distinctBy { it.address.lowercase() },
                )
            },
        )

        for (message in sent) {
            for (recipient in message.recipients) {
                index.recordSent(recipient.address, recipient.displayName, message.date)
            }
        }

        sent.maxOfOrNull { it.id }?.let { highest ->
            index.setSyncState(accountUuid, SCAN_WATERMARK_KEY, highest.toString())
        }

        return sent.size
    }

    private data class SentMessage(
        val id: Long,
        val date: Long,
        val recipients: List<RemoteContact>,
    )
}
