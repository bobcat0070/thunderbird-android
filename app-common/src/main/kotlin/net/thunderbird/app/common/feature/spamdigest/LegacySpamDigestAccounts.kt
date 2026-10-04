package net.thunderbird.app.common.feature.spamdigest

import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamDigestAccounts

internal class LegacySpamDigestAccounts(
    private val accountManager: LegacyAccountDtoManager,
) : SpamDigestAccounts {
    override fun accounts(): List<SpamDigestAccount> {
        return accountManager.getAccounts().map { account ->
            SpamDigestAccount(
                id = account.uuid,
                name = account.displayName,
                email = account.email,
            )
        }
    }
}
