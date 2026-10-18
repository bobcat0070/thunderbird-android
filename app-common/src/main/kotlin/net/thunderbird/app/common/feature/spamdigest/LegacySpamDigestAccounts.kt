package net.thunderbird.app.common.feature.spamdigest

import net.thunderbird.app.common.account.displayName
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamDigestAccounts

internal class LegacySpamDigestAccounts(
    private val accountManager: LegacyAccountManager,
) : SpamDigestAccounts {
    override fun accounts(): List<SpamDigestAccount> {
        return accountManager.findAll().map { account ->
            SpamDigestAccount(
                id = account.id.toString(),
                name = account.displayName,
                email = account.email,
            )
        }
    }
}
