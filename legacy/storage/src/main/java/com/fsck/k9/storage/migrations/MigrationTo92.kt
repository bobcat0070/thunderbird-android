package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase

/**
 * Migration to version 92.
 *
 * Adds the columns that record what kind of mail a message is.
 *
 * Existing messages are left unclassified rather than being classified in place: deciding requires headers
 * that are only available while a message is being saved, so re-classifying the whole store would mean
 * re-parsing every mailbox during an upgrade. Mail classifies as it arrives, and older mail classifies if it
 * is saved again.
 */
internal class MigrationTo92(private val db: SQLiteDatabase) {

    fun addClassificationColumns() {
        if (!columnExists("classification")) {
            db.execSQL("ALTER TABLE messages ADD classification TEXT")
        }

        if (!columnExists("classification_signal")) {
            db.execSQL("ALTER TABLE messages ADD classification_signal TEXT")
        }

        // The rules that produced a verdict, so a later improvement can re-classify only what predates it.
        if (!columnExists("classifier_version")) {
            db.execSQL("ALTER TABLE messages ADD classifier_version INTEGER DEFAULT 0")
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
