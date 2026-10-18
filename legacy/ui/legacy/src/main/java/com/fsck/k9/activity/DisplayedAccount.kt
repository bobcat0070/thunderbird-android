package com.fsck.k9.activity

import com.fsck.k9.search.isUnified
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.search.legacy.LocalMessageSearch

/**
 * @return the account the screen is about once it shows this search: as [resolveAccount] decides, except that a
 *   unified view keeps the current account.
 *
 * Unified views are switched to in place here, where upstream relaunches the screen for them and keeps the current
 * account while doing so. Without an account the drawer was never told the unified account is now shown, and went
 * on showing the previous account and its folders.
 */
internal fun LocalMessageSearch.displayedAccount(
    currentAccount: LegacyAccount?,
    accountManager: LegacyAccountManager,
): LegacyAccount? = resolveAccount(currentAccount, accountManager) ?: currentAccount?.takeIf { isUnified }
