package com.fsck.k9.controller

import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountCapabilities
import net.thunderbird.feature.account.AccountId

class RealAccountCapabilities(
    private val messagingController: MessagingController,
    private val accountManager: LegacyAccountManager,
): AccountCapabilities {
    override fun supportsUpload(accountId: AccountId): Boolean {
       val account = accountManager.findById(accountId) ?: return false
       // A server that files sent mail itself leaves nothing to upload, so there is nothing to choose.
       return messagingController.supportsUpload(account.id) && !messagingController.savesSentMessages(account.id)
    }
}
