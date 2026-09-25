@file:JvmName("LocalSearchExtensions")

package com.fsck.k9.search

import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.SearchAccount
import net.thunderbird.feature.search.legacy.SearchConditionTreeNode
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.feature.search.legacy.resolveSpecialFolders

val LocalMessageSearch.isUnifiedFolders: Boolean
    get() = id == SearchAccount.UNIFIED_FOLDERS

/**
 * The unified folder other than the inbox this search shows, or `null`. The inbox keeps its own
 * [isUnifiedFolders], which a good deal of existing behaviour is keyed on.
 */
val LocalMessageSearch.unifiedSpecialFolder: UnifiedFolderKind?
    get() = UnifiedFolderKind.fromSearchId(id)?.takeIf { it != UnifiedFolderKind.INBOX }

val LocalMessageSearch.isNewMessages: Boolean
    get() = id == SearchAccount.NEW_MESSAGES

val LocalMessageSearch.isSingleAccount: Boolean
    get() = accountUuids.size == 1

val LocalMessageSearch.isSingleFolder: Boolean
    get() = isSingleAccount && folderIds.size == 1

@Deprecated("Use getLegacyAccounts instead")
@JvmName("getAccountsFromLocalSearch")
fun LocalMessageSearch.getAccounts(accountManager: LegacyAccountDtoManager): List<LegacyAccountDto> {
    val accounts = accountManager.getAccounts()
    return if (searchAllAccounts()) {
        accounts
    } else {
        val searchAccountUuids = accountUuids.toSet()
        accounts.filter { it.uuid in searchAccountUuids }
    }
}

@JvmName("getLegacyAccountsFromLocalSearch")
fun LocalMessageSearch.getLegacyAccounts(accountManager: LegacyAccountManager): List<LegacyAccount> {
    val accounts = accountManager.getAccounts()
    return if (searchAllAccounts()) {
        accounts
    } else {
        val searchAccountUuids = accountUuids.toSet()
        accounts.filter { it.uuid in searchAccountUuids }
    }
}

fun LocalMessageSearch.getLegacyAccountUuids(accountManager: LegacyAccountManager): List<String> {
    return getLegacyAccounts(accountManager).map { it.uuid }
}

/**
 * @return the folder this account uses for [kind], or `null` when it has none. The inbox is not a special folder
 *   in this sense - the unified inbox is selected by each folder's own flag - so it resolves to nothing.
 */
fun LegacyAccount.specialFolderId(kind: UnifiedFolderKind): Long? = when (kind) {
    UnifiedFolderKind.INBOX -> null
    UnifiedFolderKind.DRAFTS -> draftsFolderId
    UnifiedFolderKind.SENT -> sentFolderId
    UnifiedFolderKind.ARCHIVE -> archiveFolderId
    UnifiedFolderKind.SPAM -> spamFolderId
    UnifiedFolderKind.TRASH -> trashFolderId
}

fun LegacyAccountDto.specialFolderId(kind: UnifiedFolderKind): Long? = when (kind) {
    UnifiedFolderKind.INBOX -> null
    UnifiedFolderKind.DRAFTS -> draftsFolderId
    UnifiedFolderKind.SENT -> sentFolderId
    UnifiedFolderKind.ARCHIVE -> archiveFolderId
    UnifiedFolderKind.SPAM -> spamFolderId
    UnifiedFolderKind.TRASH -> trashFolderId
}

/**
 * @return these conditions as they apply to one account's database; see [resolveSpecialFolders].
 */
fun SearchConditionTreeNode.forAccount(account: LegacyAccount): SearchConditionTreeNode =
    resolveSpecialFolders { kind -> account.specialFolderId(kind) }

fun SearchConditionTreeNode.forAccount(account: LegacyAccountDto): SearchConditionTreeNode =
    resolveSpecialFolders { kind -> account.specialFolderId(kind) }
