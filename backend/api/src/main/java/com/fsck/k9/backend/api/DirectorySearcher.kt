package com.fsck.k9.backend.api

/**
 * Somebody found in an organisation's directory.
 *
 * Several addresses, because a directory entry commonly carries more than one and any of them may be the one the
 * user means.
 */
data class DirectoryContact(
    val displayName: String?,
    val addresses: List<String>,
)

/**
 * A backend that can look people up in the organisation behind the account.
 *
 * Separate from [Backend] rather than part of it, because almost no mail protocol can answer this: IMAP and POP3
 * serve a mailbox and know nothing about who else exists. A caller asks a backend whether it also happens to be
 * one of these, and offers the feature only when it is.
 *
 * A search goes to the server while somebody is typing, so an implementation answers with what it has and reports
 * a failure as no matches rather than making the field wait.
 */
interface DirectorySearcher {
    fun searchDirectory(query: String): List<DirectoryContact>
}
