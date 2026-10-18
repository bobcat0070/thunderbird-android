package com.fsck.k9

import net.thunderbird.core.android.account.LegacyAccount

/**
 * Copies of an account with one setting changed, for Java tests: [LegacyAccount] is immutable and its `copy` takes
 * named arguments Java cannot pass.
 */
object LegacyAccountCopies {
    @JvmStatic
    fun withUploadSentMessages(account: LegacyAccount, isUploadSentMessages: Boolean): LegacyAccount =
        account.copy(isUploadSentMessages = isUploadSentMessages)

    @JvmStatic
    fun withDraftsFolderId(account: LegacyAccount, draftsFolderId: Long?): LegacyAccount =
        account.copy(draftsFolderId = draftsFolderId)

    @JvmStatic
    fun withRemoteSearchNumResults(account: LegacyAccount, remoteSearchNumResults: Int): LegacyAccount =
        account.copy(remoteSearchNumResults = remoteSearchNumResults)
}
