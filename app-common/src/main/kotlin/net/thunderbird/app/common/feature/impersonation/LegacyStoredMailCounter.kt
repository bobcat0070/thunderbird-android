package net.thunderbird.app.common.feature.impersonation

import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.search.specialFolderId
import net.thunderbird.app.common.feature.newsletter.NAME_SEPARATOR
import net.thunderbird.app.common.feature.newsletter.SENDER_ADDRESS_CONDITION
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.impersonation.StoredMailCounter
import net.thunderbird.feature.search.legacy.UnifiedFolderKind

/**
 * Counts a sender's stored mail in every account, stopping as soon as it has counted enough.
 *
 * Spam is left out: earlier mail from an address that landed there does not make the address one the reader knows.
 */
internal class LegacyStoredMailCounter(
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
) : StoredMailCounter {

    override fun countMailFrom(senderAddress: String, atMost: Int): Int {
        val senderArgument = senderAddress.trim().lowercase() + NAME_SEPARATOR
        var count = 0

        for (account in accountManager.findAll()) {
            if (count >= atMost) break

            val spamFolderId = account.specialFolderId(UnifiedFolderKind.SPAM)
            val selection = if (spamFolderId == null) {
                SENDER_ADDRESS_CONDITION
            } else {
                "$SENDER_ADDRESS_CONDITION AND messages.folder_id != ?"
            }
            val selectionArgs = listOfNotNull(senderArgument, spamFolderId?.toString()).toTypedArray()

            count += messageStoreManager.getMessageStore(account.id).getMessages(
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = "messages.id",
                messageMapper = { },
                limit = atMost - count,
            ).size
        }

        return count
    }
}
