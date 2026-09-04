package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase

/**
 * A `messages` table as it stood at schema version 91, before anything about classification existed.
 *
 * Written out rather than built from the current schema definition on purpose: a migration test is only
 * worth anything if it runs against the database people actually have, and generating the "before" state
 * from today's code would make the test agree with itself no matter what the migration did.
 *
 * @param extraColumns column definitions added to the table, for starting from a later version.
 */
internal fun createMessagesTableVersion91(vararg extraColumns: String): SQLiteDatabase {
    return SQLiteDatabase.create(null).apply {
        val columns = listOf(
            "id INTEGER PRIMARY KEY",
            "deleted INTEGER default 0",
            "folder_id INTEGER",
            "uid TEXT",
            "subject TEXT",
            "date INTEGER",
            "flags TEXT",
            "sender_list TEXT",
            "to_list TEXT",
            "cc_list TEXT",
            "bcc_list TEXT",
            "reply_to_list TEXT",
            "attachment_count INTEGER",
            "internal_date INTEGER",
            "message_id TEXT",
            "preview_type TEXT default \"none\"",
            "preview TEXT",
            "mime_type TEXT",
            "normalized_subject_hash INTEGER",
            "empty INTEGER default 0",
            "read INTEGER default 0",
            "flagged INTEGER default 0",
            "answered INTEGER default 0",
            "forwarded INTEGER default 0",
            "message_part_id INTEGER",
            "encryption_type TEXT",
            "new_message INTEGER default 0",
            "account_id TEXT",
        ) + extraColumns

        execSQL("CREATE TABLE messages (${columns.joinToString(", ")})")
    }
}

/**
 * @return the names of the columns on the `messages` table, as the migration itself reads them.
 */
internal fun SQLiteDatabase.messageColumnNames(): List<String> {
    return rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
    }
}
