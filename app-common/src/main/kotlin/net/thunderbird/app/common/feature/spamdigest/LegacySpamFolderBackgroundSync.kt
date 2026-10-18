package net.thunderbird.app.common.feature.spamdigest

import app.k9mail.legacy.mailstore.MessageStoreManager
import net.thunderbird.app.common.account.getByUuid
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync

/**
 * The spam folder's own "synchronize" folder setting - the same switch the folder's settings screen shows, so what
 * the alert turns on is visible there and can be turned off there too.
 */
internal class LegacySpamFolderBackgroundSync(
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
) : SpamFolderBackgroundSync {

    override fun isSyncEnabled(accountId: String): Boolean? {
        val account = accountManager.getByUuid(accountId) ?: return null

        return account.spamFolderId?.let { spamFolderId ->
            messageStoreManager.getMessageStore(account.id).getFolder(spamFolderId) { it.isSyncEnabled }
        }
    }

    override fun setSyncEnabled(accountId: String, enabled: Boolean) {
        val account = accountManager.getByUuid(accountId) ?: return
        val spamFolderId = account.spamFolderId ?: return

        messageStoreManager.getMessageStore(account.id).setSyncEnabled(spamFolderId, enabled)
    }
}
