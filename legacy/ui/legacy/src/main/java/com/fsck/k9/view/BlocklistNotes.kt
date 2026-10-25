@file:JvmName("BlocklistNotes")

package com.fsck.k9.view

import android.content.Context
import com.fsck.k9.ui.R
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.blocklist.BlocklistMatch

/**
 * What to say about a link whose site is on a blocklist.
 */
fun blocklistNote(context: Context, match: BlocklistMatch): String = when (match.kind) {
    BlocklistKind.THREAT -> context.getString(R.string.blocklist_link_threat, match.listedDomain)
    BlocklistKind.GENERAL -> context.getString(R.string.blocklist_link_general, match.listedDomain, match.listName)
}

/**
 * What to say under a sender whose domain is on a blocklist.
 */
fun senderBlocklistNote(context: Context, match: BlocklistMatch): String = when (match.kind) {
    BlocklistKind.THREAT -> context.getString(R.string.blocklist_sender_threat, match.listedDomain)
    BlocklistKind.GENERAL -> context.getString(R.string.blocklist_sender_general, match.listedDomain, match.listName)
}
