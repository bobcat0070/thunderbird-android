package net.thunderbird.app.common.account

import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory

/**
 * The account with this id as a string, or `null` when there is none - or the string is not an account id at all.
 *
 * The fork's features keep accounts by the string form of their id, in settings and stores of their own, where the
 * accounts themselves are now looked up by [net.thunderbird.feature.account.AccountId].
 */
internal fun LegacyAccountManager.getByUuid(accountUuid: String): LegacyAccount? {
    val accountId = runCatching { AccountIdFactory.of(accountUuid) }.getOrNull() ?: return null
    return findById(accountId)
}

/**
 * The name the account is shown under: its own name, or its email address when it has none - as notifications show it.
 */
internal val LegacyAccount.displayName: String
    get() = name?.takeIf { it.isNotBlank() } ?: email
