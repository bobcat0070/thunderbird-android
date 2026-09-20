package com.fsck.k9.mailstore.recipients

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Where an address became known.
 *
 * Kept on the row because it decides what may remove it: a contact fetched from an account disappears when
 * that account's contacts no longer list it, while what the user's own sent mail implies is theirs and is only
 * ever added to.
 */
enum class RecipientOrigin {
    /** Someone the user has sent mail to. */
    HISTORY,

    /** A contact held by the account's own provider, fetched from it. */
    REMOTE,
}

/**
 * An address worth offering, and what is known about it.
 *
 * @param timesUsed how many messages the user has sent to this address. Zero for a contact nobody has written
 *   to yet, which still deserves to be offered - just below the people who have been written to.
 */
data class IndexedRecipient(
    val address: String,
    val displayName: String?,
    val origin: RecipientOrigin,
    val timesUsed: Int,
    val lastUsed: Long,
)

/**
 * The addresses worth offering while a message is being addressed.
 *
 * Two things are recorded here, and they answer different halves of the same question. Mail the user has sent
 * says who they actually write to, which is the best ranking signal there is and needs no permission and no
 * network. Contacts fetched from an account say who exists, which is what makes a colleague completable before
 * they have ever been written to.
 *
 * Reads happen on every keystroke of a recipient field, so a search is one indexed query and never a scan.
 */
class RecipientIndex internal constructor(
    private val helper: RecipientIndexDatabase,
) {
    constructor(context: Context) : this(RecipientIndexDatabase(context))

    /**
     * Records that a message was sent to [address].
     *
     * Counts up rather than replacing, because the count is the ranking: someone written to weekly should
     * outrank someone written to once, however recently. A display name is only written when one is offered
     * and the row has none, so a mailing list's own name does not overwrite the name of a person.
     */
    fun recordSent(address: String, displayName: String?, at: Long) {
        val normalized = address.normalizedAddress() ?: return

        helper.writableDatabase.transaction {
            val updated = execUpdate(
                "UPDATE $TABLE_RECIPIENTS SET times_used = times_used + 1," +
                    " last_used = MAX(last_used, ?)," +
                    " display_name = COALESCE(display_name, ?)," +
                    " source = ?" +
                    " WHERE address = ?",
                arrayOf(at, displayName, RecipientOrigin.HISTORY.name, normalized),
            )

            if (updated == 0) {
                insert(
                    address = normalized,
                    displayName = displayName,
                    origin = RecipientOrigin.HISTORY,
                    accountUuid = null,
                    timesUsed = 1,
                    lastUsed = at,
                )
            }
        }
    }

    /**
     * Records a contact held by an account's own provider.
     *
     * Leaves the send count alone: whether the user writes to someone is not something their address book has
     * an opinion about, and a contact that was also written to keeps the ranking it earned.
     */
    fun recordRemoteContacts(accountUuid: String, contacts: List<RemoteContact>) {
        if (contacts.isEmpty()) return

        helper.writableDatabase.transaction {
            for (contact in contacts) {
                val normalized = contact.address.normalizedAddress() ?: continue
                val updated = execUpdate(
                    "UPDATE $TABLE_RECIPIENTS SET display_name = COALESCE(?, display_name)," +
                        " account_uuid = ?" +
                        " WHERE address = ?",
                    arrayOf(contact.displayName, accountUuid, normalized),
                )

                if (updated == 0) {
                    insert(
                        address = normalized,
                        displayName = contact.displayName,
                        origin = RecipientOrigin.REMOTE,
                        accountUuid = accountUuid,
                        timesUsed = 0,
                        lastUsed = 0,
                    )
                }
            }
        }
    }

    /**
     * Forgets contacts fetched from [accountUuid] that were never written to.
     *
     * Rows the user has written to are kept whatever the address book says: the account no longer listing
     * someone is not a reason to stop completing an address the user demonstrably uses.
     */
    fun removeRemoteContacts(accountUuid: String, addresses: Collection<String>) {
        val normalized = addresses.mapNotNull { it.normalizedAddress() }
        if (normalized.isEmpty()) return

        helper.writableDatabase.transaction {
            for (address in normalized) {
                execUpdate(
                    "DELETE FROM $TABLE_RECIPIENTS WHERE address = ? AND account_uuid = ? AND times_used = 0",
                    arrayOf(address, accountUuid),
                )
            }
        }
    }

    /**
     * Forgets everything fetched from [accountUuid], for when the account is removed.
     */
    fun removeAccount(accountUuid: String) {
        helper.writableDatabase.delete(
            TABLE_RECIPIENTS,
            "account_uuid = ? AND times_used = 0",
            arrayOf(accountUuid),
        )
        helper.writableDatabase.delete(TABLE_SYNC_STATE, "account_uuid = ?", arrayOf(accountUuid))
    }

    /**
     * @return the addresses matching [query], best first, at most [limit] of them.
     *
     * Matches a prefix of the address or of any word of the name, which is what someone typing expects: "sam"
     * should find "Sam Vimes" and "sam@example.com", and typing a surname should find them too. A substring
     * match anywhere would also match "notsam@example.com", which is noise rather than help.
     *
     * Ranked by whether the address itself starts with what was typed, then by how often the user has written
     * to it, then by how recently. Someone written to often is nearly always who was meant.
     */
    fun search(query: String, limit: Int): List<IndexedRecipient> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val prefix = trimmed.escapedForLike() + "%"
        val wordPrefix = "% " + trimmed.escapedForLike() + "%"

        return helper.readableDatabase.rawQuery(
            "SELECT address, display_name, source, times_used, last_used FROM $TABLE_RECIPIENTS" +
                " WHERE address LIKE ? ESCAPE '\\'" +
                " OR display_name LIKE ? ESCAPE '\\'" +
                " OR display_name LIKE ? ESCAPE '\\'" +
                " ORDER BY (address LIKE ? ESCAPE '\\') DESC, times_used DESC, last_used DESC, address ASC" +
                " LIMIT ?",
            arrayOf(prefix, prefix, wordPrefix, prefix, limit.toString()),
        ).use { cursor -> cursor.toRecipients() }
    }

    /**
     * @return the addresses written to most, best first, for the suggestions offered before anything is typed.
     *
     * Only addresses the user has actually written to: a contact nobody has corresponded with is worth
     * completing once its name is typed, but it is not a suggestion.
     */
    fun mostUsed(limit: Int): List<IndexedRecipient> {
        return helper.readableDatabase.rawQuery(
            "SELECT address, display_name, source, times_used, last_used FROM $TABLE_RECIPIENTS" +
                " WHERE times_used > 0" +
                " ORDER BY times_used DESC, last_used DESC, address ASC" +
                " LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor -> cursor.toRecipients() }
    }

    /**
     * @return how many messages the user has sent to [address].
     */
    fun timesSentTo(address: String): Int {
        val normalized = address.normalizedAddress() ?: return 0

        return helper.readableDatabase.rawQuery(
            "SELECT times_used FROM $TABLE_RECIPIENTS WHERE address = ?",
            arrayOf(normalized),
        ).use { cursor -> if (cursor.moveToNext()) cursor.getInt(0) else 0 }
    }

    /**
     * @return every address the user has sent at least [minimumMessages] messages to.
     *
     * Answers the question the message classifier asks - whether a sender is someone this user corresponds
     * with - from the same rows that feed completion, so one scan serves both.
     */
    fun addressesWrittenTo(minimumMessages: Int): Set<String> {
        return helper.readableDatabase.rawQuery(
            "SELECT address FROM $TABLE_RECIPIENTS WHERE times_used >= ?",
            arrayOf(minimumMessages.toString()),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    /**
     * Remembers where a provider's contact sync got to, so the next one asks only for what changed.
     */
    fun syncState(accountUuid: String, key: String): String? {
        return helper.readableDatabase.rawQuery(
            "SELECT value FROM $TABLE_SYNC_STATE WHERE account_uuid = ? AND key = ?",
            arrayOf(accountUuid, key),
        ).use { cursor -> if (cursor.moveToNext()) cursor.getString(0) else null }
    }

    fun setSyncState(accountUuid: String, key: String, value: String?) {
        val values = ContentValues().apply {
            put("account_uuid", accountUuid)
            put("key", key)
            put("value", value)
        }

        helper.writableDatabase.insertWithOnConflict(
            TABLE_SYNC_STATE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun SQLiteDatabase.insert(
        address: String,
        displayName: String?,
        origin: RecipientOrigin,
        accountUuid: String?,
        timesUsed: Int,
        lastUsed: Long,
    ) {
        val values = ContentValues().apply {
            put("address", address)
            put("display_name", displayName)
            put("source", origin.name)
            put("account_uuid", accountUuid)
            put("times_used", timesUsed)
            put("last_used", lastUsed)
        }

        insertWithOnConflict(TABLE_RECIPIENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }
}

private const val COLUMN_ADDRESS = 0
private const val COLUMN_DISPLAY_NAME = 1
private const val COLUMN_SOURCE = 2
private const val COLUMN_TIMES_USED = 3
private const val COLUMN_LAST_USED = 4

private fun android.database.Cursor.toRecipients(): List<IndexedRecipient> = buildList {
    while (moveToNext()) {
        add(
            IndexedRecipient(
                address = getString(COLUMN_ADDRESS),
                displayName = getString(COLUMN_DISPLAY_NAME),
                origin = RecipientOrigin.entries.firstOrNull { it.name == getString(COLUMN_SOURCE) }
                    ?: RecipientOrigin.HISTORY,
                timesUsed = getInt(COLUMN_TIMES_USED),
                lastUsed = getLong(COLUMN_LAST_USED),
            ),
        )
    }
}

/**
 * A contact as an account's own provider holds it.
 */
data class RemoteContact(
    val address: String,
    val displayName: String?,
)

private fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

/**
 * @return how many rows the statement changed.
 *
 * `execSQL` does not say, and whether a row already existed is what decides between counting up and inserting.
 */
private fun SQLiteDatabase.execUpdate(sql: String, arguments: Array<Any?>): Int {
    compileStatement(sql).use { statement ->
        arguments.forEachIndexed { index, argument ->
            val position = index + 1
            when (argument) {
                null -> statement.bindNull(position)
                is Long -> statement.bindLong(position, argument)
                is Int -> statement.bindLong(position, argument.toLong())
                else -> statement.bindString(position, argument.toString())
            }
        }

        return statement.executeUpdateDelete()
    }
}

internal fun String.normalizedAddress(): String? = trim().lowercase().takeIf { it.contains('@') && it.length > 2 }

/**
 * Escapes what LIKE treats as a pattern, so someone typing "%" searches for it rather than matching everything.
 */
private fun String.escapedForLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
