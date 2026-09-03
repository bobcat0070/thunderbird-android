package com.fsck.k9.account

import net.thunderbird.core.android.account.DeletePolicy
import net.thunderbird.core.common.mail.Protocols

class DefaultDeletePolicyProvider : DeletePolicyProvider {
    override fun getDeletePolicy(accountType: String): DeletePolicy {
        return when (accountType) {
            Protocols.IMAP -> DeletePolicy.ON_DELETE
            Protocols.POP3 -> DeletePolicy.NEVER
            // Deleting through Graph moves the message to Deleted Items, the same as IMAP.
            Protocols.GRAPH -> DeletePolicy.ON_DELETE
            "demo" -> DeletePolicy.ON_DELETE
            else -> throw AssertionError("Unhandled case: $accountType")
        }
    }
}
