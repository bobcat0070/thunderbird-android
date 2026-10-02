package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.storage.messages.toDatabaseValue

/**
 * Migration to version 95.
 *
 * Adds the columns for how important the sender marked a message and for the categories the server keeps on it.
 *
 * Importance is stated in a message's headers, so messages that are already stored get theirs from the headers
 * stored with them. Categories are not part of a message at all; they are filled in by the next synchronization.
 */
internal class MigrationTo95(private val db: SQLiteDatabase) {

    fun addImportanceAndServerCategoriesColumns() {
        if (!columnExists("importance")) {
            db.execSQL("ALTER TABLE messages ADD importance INTEGER DEFAULT 0")
            readImportanceFromStoredHeaders()
        }

        if (!columnExists("server_categories")) {
            db.execSQL("ALTER TABLE messages ADD server_categories TEXT")
        }
    }

    private fun readImportanceFromStoredHeaders() {
        val messageIdsByImportance = mutableMapOf<MessageImportance, MutableList<Long>>()

        // The cast makes the pattern apply to the header as text; without it SQLite may be built to never match
        // a BLOB. LIKE ignores case, so this also finds "X-Priority". Most messages state neither, which is what
        // keeps the rows read here to a small share of the mailbox.
        db.rawQuery(
            """
            SELECT messages.id, message_parts.header
            FROM messages
            JOIN message_parts ON (message_parts.id = messages.message_part_id)
            WHERE CAST(message_parts.header AS TEXT) LIKE '%importance:%'
              OR CAST(message_parts.header AS TEXT) LIKE '%x-priority:%'
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val header = cursor.getBlob(1)?.toString(Charsets.ISO_8859_1) ?: continue
                val importance = MessageImportance.fromHeaders(
                    importance = header.headerValue(MessageImportance.HEADER_IMPORTANCE),
                    priority = header.headerValue(MessageImportance.HEADER_PRIORITY),
                )

                if (importance != MessageImportance.NORMAL) {
                    messageIdsByImportance.getOrPut(importance) { mutableListOf() } += cursor.getLong(0)
                }
            }
        }

        for ((importance, messageIds) in messageIdsByImportance) {
            for (chunk in messageIds.chunked(UPDATE_CHUNK_SIZE)) {
                db.execSQL(
                    "UPDATE messages SET importance = ${importance.toDatabaseValue()} " +
                        "WHERE id IN (${chunk.joinToString(",")})",
                )
            }
        }
    }

    /**
     * The value of the first header field called [name], which has to start a line to be one.
     */
    private fun String.headerValue(name: String): String? {
        return lineSequence()
            .firstOrNull { line -> line.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
    }

    private fun columnExists(columnName: String): Boolean {
        db.rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) return true
            }
        }

        return false
    }

    private companion object {
        const val UPDATE_CHUNK_SIZE = 500
    }
}
