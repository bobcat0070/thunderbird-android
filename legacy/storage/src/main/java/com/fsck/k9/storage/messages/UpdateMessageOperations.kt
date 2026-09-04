package com.fsck.k9.storage.messages

import android.content.ContentValues
import com.fsck.k9.mailstore.LockableDatabase
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope

internal class UpdateMessageOperations(private val lockableDatabase: LockableDatabase) {

    fun setNewMessageState(folderId: Long, messageServerId: String, newMessage: Boolean) {
        lockableDatabase.execute(false) { database ->
            val values = ContentValues().apply {
                put("new_message", if (newMessage) 1 else 0)
            }

            database.update(
                "messages",
                values,
                "folder_id = ? AND uid = ?",
                arrayOf(folderId.toString(), messageServerId),
            )
        }
    }

    fun clearNewMessageState() {
        lockableDatabase.execute(false) { database ->
            database.execSQL("UPDATE messages SET new_message = 0")
        }
    }

    /**
     * Re-classifies every stored message from [pattern] as [messageClass].
     *
     * Written into the stored rows rather than applied when the list is drawn, so that the classification
     * column stays the single source of truth: message list queries filter and sort on it in SQL, and a
     * correction the user just made has to be visible to those queries.
     *
     * @return how many messages were re-classified.
     */
    fun setClassificationForSender(
        scope: RuleScope,
        pattern: String,
        messageClass: MessageClass,
        signal: String,
        classifierVersion: Int,
    ): Int {
        val normalized = pattern.trim().lowercase()
        if (normalized.isEmpty()) return 0

        val values = ContentValues().apply {
            put("classification", messageClass.name)
            put("classification_signal", signal)
            put("classifier_version", classifierVersion)
        }

        return lockableDatabase.execute(false) { database ->
            database.update("messages", values, SENDER_MATCH_CLAUSE, senderMatchArgs(scope, normalized))
        }
    }
}

/**
 * `sender_list` holds addresses packed as `address;<0x01>personal`, joined by `,<0x01>`. Matching that packed
 * form directly in SQL avoids unpacking every row in the mailbox to compare a single address.
 *
 * Two patterns per rule because the personal name is optional: the address is either the entire column, or it
 * is followed by the separator. Matching on the `;` alone is enough, since an address cannot
 * contain one, and it keeps a control character out of the query.
 */
private const val PACKED_ADDRESS_SEPARATOR = ";"

/**
 * `~` as the LIKE escape rather than the conventional backslash, because a backslash has to be escaped again
 * in the Kotlin literal and in SQL, and three layers of escaping around a query that can rewrite every row in
 * the mailbox is not worth the convention.
 */
private const val LIKE_ESCAPE = '~'

private const val SENDER_MATCH_CLAUSE =
    "LOWER(sender_list) LIKE ? ESCAPE '~' OR LOWER(sender_list) LIKE ? ESCAPE '~'"

private fun senderMatchArgs(scope: RuleScope, pattern: String): Array<String> {
    val escaped = pattern.escapeLikeWildcards()

    return when (scope) {
        // No wildcards, so the first pattern is an exact comparison.
        RuleScope.SENDER -> arrayOf(escaped, "$escaped$PACKED_ADDRESS_SEPARATOR%")

        RuleScope.DOMAIN -> arrayOf("%@$escaped", "%@$escaped$PACKED_ADDRESS_SEPARATOR%")
    }
}

/**
 * Escapes the LIKE wildcards. `_` in particular is ordinary in an address local part, and left unescaped it
 * would quietly match addresses the user never corrected.
 */
private fun String.escapeLikeWildcards(): String = buildString {
    for (character in this@escapeLikeWildcards) {
        if (character == '%' || character == '_' || character == LIKE_ESCAPE) {
            append(LIKE_ESCAPE)
        }
        append(character)
    }
}
