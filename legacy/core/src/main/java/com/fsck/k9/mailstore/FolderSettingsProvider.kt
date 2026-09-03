package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.FolderSettings
import com.fsck.k9.Preferences
import com.fsck.k9.mail.FolderType
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId

/**
 * Provides imported folder settings if available, otherwise default values.
 */
class FolderSettingsProvider(
    val preferences: Preferences,
    val accountManager: LegacyAccountManager,
    val accountId: AccountId,
) {
    fun getFolderSettings(folderServerId: String, folderType: FolderType): FolderSettings {
        val storage = preferences.storage
        val prefix = "$accountId.$folderServerId"
        val account = getAccountById(accountId)

        return FolderSettings(
            visibleLimit = account.displayCount,
            isVisible = storage.getBoolean("$prefix.visible", true),
            isSyncEnabled = storage.getBoolean("$prefix.syncEnabled", false),
            isNotificationsEnabled = storage.getBoolean("$prefix.notificationsEnabled", false),
            isPushEnabled = storage.getBoolean(
                "$prefix.pushEnabled",
                isPushEnabledByDefault(account.incomingServerSettings.type, folderType),
            ),
            inTopGroup = storage.getBoolean("$prefix.inTopGroup", false),
            integrate = storage.getBoolean("$prefix.integrate", false),
        ).also {
            removeImportedFolderSettings(prefix)
        }
    }

    private fun getAccountById(accountId: AccountId) =
        accountManager.getByIdSync(accountId)
            ?: error("Account not found: $accountId")

    private fun removeImportedFolderSettings(prefix: String) {
        val editor = preferences.createStorageEditor()

        editor.remove("$prefix.visible")
        editor.remove("$prefix.syncEnabled")
        editor.remove("$prefix.notificationsEnabled")
        editor.remove("$prefix.pushEnabled")
        editor.remove("$prefix.inTopGroup")
        editor.remove("$prefix.integrate")

        editor.commit()
    }
}

/**
 * Whether a newly discovered folder should have push enabled without the user asking for it.
 *
 * Microsoft Graph has no equivalent of IMAP IDLE, and its background synchronization is scheduled with periodic work,
 * which the platform will not run more often than every fifteen minutes. Push is what makes such an account behave
 * the way people expect mail to behave, so its inbox opts in from the start. Protocols that can be checked promptly
 * by other means keep the previous default of leaving push off, since turning it on costs a permanent notification.
 */
internal fun isPushEnabledByDefault(incomingServerType: String, folderType: FolderType): Boolean {
    return folderType == FolderType.INBOX && incomingServerType == Protocols.GRAPH
}
