package net.thunderbird.feature.navigation.drawer.dropdown.domain.entity

import net.thunderbird.feature.search.legacy.UnifiedFolderKind

/**
 * Represents a unified folder in the drawer.
 *
 * The id is unique for each unified folder type. The inbox keeps the id it has always had in the drawer; the others
 * share their search's id, so a displayed search can be matched back to its entry.
 */
internal enum class UnifiedDisplayFolderType(
    val id: String,
    val kind: UnifiedFolderKind,
) {
    INBOX("unified_inbox", UnifiedFolderKind.INBOX),
    DRAFTS(UnifiedFolderKind.DRAFTS.searchId, UnifiedFolderKind.DRAFTS),
    SENT(UnifiedFolderKind.SENT.searchId, UnifiedFolderKind.SENT),
    ARCHIVE(UnifiedFolderKind.ARCHIVE.searchId, UnifiedFolderKind.ARCHIVE),
    SPAM(UnifiedFolderKind.SPAM.searchId, UnifiedFolderKind.SPAM),
    TRASH(UnifiedFolderKind.TRASH.searchId, UnifiedFolderKind.TRASH),
    ;

    companion object {
        fun fromKind(kind: UnifiedFolderKind): UnifiedDisplayFolderType = entries.first { it.kind == kind }
    }
}
