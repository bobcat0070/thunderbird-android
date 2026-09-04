package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.MessageListRepository
import net.thunderbird.core.android.account.LegacyAccountDtoManager

/**
 * How long a scan is reused before the sent folders are read again.
 *
 * Long, because the answer barely moves: someone you have written to stays someone you have written to, and
 * the cost of being a few minutes stale is that one message from a brand-new correspondent is classified
 * without this signal.
 */
private const val CACHE_LIFETIME_MILLIS = 30L * 60L * 1000L

/**
 * How long an empty result is reused.
 *
 * Much shorter, because empty usually means the sent folder has not been synchronised yet rather than that
 * the user has never written to anyone. Holding that answer for half an hour would ignore the signal for the
 * first half hour of a new account, which is exactly when a mailbox is being filled and classified.
 */
private const val EMPTY_CACHE_LIFETIME_MILLIS = 60L * 1000L

/**
 * How many messages must have been sent to an address before it counts as correspondence.
 *
 * One is not enough. Replying once to a marketing address is common, and a single reply would otherwise
 * promote everything that sender ever sends above its own bulk headers. Measured against a real mailbox, this
 * is exactly the line between a support thread someone actually had and a one-off reply to a mailshot.
 */
private const val MINIMUM_MESSAGES_SENT = 2

/**
 * The addresses the user has written to.
 *
 * Someone the user has actually sent mail to is a correspondent, and mail from them is worth reading even
 * when it carries the bulk headers a company mail gateway staples onto everything. This is the strongest
 * evidence available that an address matters to this particular person, and unlike a header it cannot be set
 * by the sender.
 *
 * Read from the sent folders rather than tracked as mail is sent, so it works from the first run against
 * history that already exists.
 */
class KnownCorrespondents(
    private val accountManager: LegacyAccountDtoManager,
    private val messageListRepository: MessageListRepository,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var addresses: Set<String> = emptySet()
    private var loadedAt = 0L

    /**
     * @return whether the user has sent mail to [emailAddress].
     */
    fun isKnown(emailAddress: String): Boolean {
        val address = emailAddress.trim().lowercase()
        if (address.isEmpty()) return false

        return address in currentAddresses()
    }

    private fun currentAddresses(): Set<String> {
        synchronized(lock) {
            val lifetime = if (addresses.isEmpty()) EMPTY_CACHE_LIFETIME_MILLIS else CACHE_LIFETIME_MILLIS
            if (currentTimeMillis() - loadedAt > lifetime) {
                addresses = loadAddresses()
                loadedAt = currentTimeMillis()
            }

            return addresses
        }
    }

    private fun loadAddresses(): Set<String> {
        val counts = mutableMapOf<String, Int>()

        for (account in accountManager.getAccounts()) {
            val sentFolderId = account.sentFolderId ?: continue

            // One unreadable account must not empty the set for the others; the worst case is that this
            // signal is missing, which only ever means a message is classified with less evidence.
            runCatching {
                for (address in recipientsOf(account.uuid, sentFolderId)) {
                    counts[address] = (counts[address] ?: 0) + 1
                }
            }
        }

        // Counted across accounts rather than per account: writing to someone from either address is still
        // corresponding with them.
        return counts.filterValues { it >= MINIMUM_MESSAGES_SENT }.keys
    }

    /**
     * @return one entry per message sent to an address, so repeats can be counted. An address named twice in
     *   the same message counts once: that is one message, not two.
     */
    private fun recipientsOf(accountUuid: String, folderId: Long): List<String> {
        return messageListRepository.getMessages(
            accountUuid = accountUuid,
            selection = "folder_id = ?",
            selectionArgs = arrayOf(folderId.toString()),
            sortOrder = "date DESC",
            messageMapper = { message ->
                (message.toAddresses + message.ccAddresses)
                    .mapNotNull { it.address?.lowercase() }
                    .distinct()
            },
        ).flatten()
    }
}
