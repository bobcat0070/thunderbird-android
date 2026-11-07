@file:JvmName("LocalSearchExtensions")

package com.fsck.k9.search

import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.FolderRole
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.LocalMessageSearchType
import net.thunderbird.feature.search.legacy.SearchConditionTreeNode
import net.thunderbird.feature.search.legacy.UnifiedFolderKind
import net.thunderbird.feature.search.legacy.UnifiedFolderSelection
import net.thunderbird.feature.search.legacy.resolveSpecialFolders

val LocalMessageSearch.isUnified: Boolean
    get() = type is LocalMessageSearchType.Unified

/**
 * The role of the folders a unified search shows, or `null` if this isn't a unified search of a special folder.
 */
val LocalMessageSearch.unifiedFolderRole: FolderRole?
    get() = ((type as? LocalMessageSearchType.Unified)?.folder as? UnifiedFolderSelection.Special)?.role

/**
 * The unified folder other than the inbox this search shows, or `null`. The inbox keeps its own
 * [isUnifiedInbox], which a good deal of existing behaviour is keyed on.
 */
val LocalMessageSearch.unifiedSpecialFolder: UnifiedFolderKind?
    get() = UnifiedFolderKind.fromSearch(this)?.takeIf { it != UnifiedFolderKind.INBOX }

/**
 * Whether this search is the unified inbox, showing every account's inbox.
 */
val LocalMessageSearch.isUnifiedInbox: Boolean
    get() = unifiedFolderRole == FolderRole.INBOX

val LocalMessageSearch.isNewMessages: Boolean
    get() = type == LocalMessageSearchType.NewMessages

val LocalMessageSearch.isSingleAccount: Boolean
    get() = accountIds.size == 1

val LocalMessageSearch.isSingleFolder: Boolean
    get() = isSingleAccount && folderIds.size == 1

/**
 * Returns the conditions to run this search against [account]'s messages, or `null` if the account doesn't take part.
 *
 * Both ways of naming a folder by role are resolved to the account's own folder: the search's unified folder (see
 * [LocalMessageSearch.conditionsForAccount]) and any special-folder condition in it (see [forAccount]).
 */
fun LocalMessageSearch.conditionsFor(
    account: LegacyAccount,
    outboxFolderManager: OutboxFolderManager,
): SearchConditionTreeNode? = conditionsForAccount { role ->
    account.folderIdFor(role, outboxFolderManager)
}?.forAccount(account)

/**
 * Returns the ID of this account's folder with the given [role], or `null` if the account doesn't have one.
 */
fun LegacyAccount.folderIdFor(role: FolderRole, outboxFolderManager: OutboxFolderManager): Long? {
    return when (role) {
        FolderRole.INBOX -> inboxFolderId
        FolderRole.OUTBOX -> outboxFolderManager.getOutboxFolderIdSync(id).takeIf { it != -1L }
        FolderRole.SENT -> sentFolderId
        FolderRole.TRASH -> trashFolderId
        FolderRole.DRAFTS -> draftsFolderId
        FolderRole.ARCHIVE -> archiveFolderId
        FolderRole.SPAM -> spamFolderId
    }
}

/**
 * Returns the ID of each account's folder with the given [role], leaving out accounts that don't have one.
 */
fun LegacyAccountManager.folderIdsFor(
    role: FolderRole,
    outboxFolderManager: OutboxFolderManager,
): Map<AccountId, Long> {
    return findAll()
        .mapNotNull { account -> account.folderIdFor(role, outboxFolderManager)?.let { account.id to it } }
        .toMap()
}

@JvmName("getLegacyAccountsFromLocalSearch")
fun LocalMessageSearch.getLegacyAccounts(accountManager: LegacyAccountManager): List<LegacyAccount> {
    val accounts = accountManager.findAll()
    return if (searchAllAccounts()) {
        accounts
    } else {
        val searchAccountIds = accountIds.toSet()
        accounts.filter { it.id in searchAccountIds }
    }
}

fun LocalMessageSearch.getLegacyAccountUuids(accountManager: LegacyAccountManager): List<String> {
    return getLegacyAccounts(accountManager).map { it.id.toString() }
}

/**
 * @return the folder this account uses for [kind], or `null` when it has none.
 */
fun LegacyAccount.specialFolderId(kind: UnifiedFolderKind): Long? = when (kind) {
    UnifiedFolderKind.INBOX -> inboxFolderId
    UnifiedFolderKind.DRAFTS -> draftsFolderId
    UnifiedFolderKind.SENT -> sentFolderId
    UnifiedFolderKind.ARCHIVE -> archiveFolderId
    UnifiedFolderKind.SPAM -> spamFolderId
    UnifiedFolderKind.TRASH -> trashFolderId
}

/**
 * @return these conditions as they apply to one account's database; see [resolveSpecialFolders]. Without the
 *   account, a condition naming a folder by role matches nothing, as for an account that has no such folder.
 */
fun SearchConditionTreeNode.forAccount(account: LegacyAccount?): SearchConditionTreeNode =
    resolveSpecialFolders { kind -> account?.specialFolderId(kind) }
