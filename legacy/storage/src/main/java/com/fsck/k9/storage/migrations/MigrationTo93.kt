package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase

/**
 * Migration to version 93.
 *
 * Adds the column recording whether the receiving server reported that a message passed DMARC.
 *
 * Existing messages default to not authenticated. That is the safe direction: the flag exists to decide
 * whether a sender's brand logo may be shown, and defaulting to "yes" for every message already stored would
 * hand a brand indicator to mail that was never checked.
 */
internal class MigrationTo93(private val db: SQLiteDatabase) {

    fun addSenderAuthenticationColumn() {
        if (!columnExists("sender_authenticated")) {
            db.execSQL("ALTER TABLE messages ADD sender_authenticated INTEGER DEFAULT 0")
        }
    }

    private fun columnExists(columnName: String): Boolean {
        db.rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) return true
            }
        }

        return false
    }
}
