package com.fsck.k9.mailstore.recipients

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal const val TABLE_RECIPIENTS = "recipients"
internal const val TABLE_SYNC_STATE = "recipient_sync_state"

private const val DATABASE_NAME = "recipients.db"
private const val DATABASE_VERSION = 1

/**
 * Holds the addresses worth offering while a message is being addressed.
 *
 * Its own database rather than a table in an account's store, because a message store is per account and this
 * is not: someone written to from one address is still someone to offer when writing from another, and the
 * alternative is asking every account in turn on every keystroke.
 *
 * Nothing here is authoritative. Every row is either derived from mail the user sent, fetched from an account's
 * own contacts, or read from the device, and any of them can be rebuilt by scanning again. That is what makes
 * it safe to drop the whole database on a schema change rather than migrating it.
 */
internal class RecipientIndexDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_RECIPIENTS (" +
                // Addresses are compared without regard to case, and the collation is what lets a prefix
                // search use the index rather than reading every row.
                "address TEXT PRIMARY KEY COLLATE NOCASE," +
                "display_name TEXT," +
                "source TEXT NOT NULL," +
                // Which account the address came from, for a row fetched from an account's own contacts, so
                // removing that account takes its contacts with it. Null for what the user's own mail implies.
                "account_uuid TEXT," +
                "times_used INTEGER NOT NULL DEFAULT 0," +
                "last_used INTEGER NOT NULL DEFAULT 0" +
                ")",
        )
        db.execSQL("CREATE INDEX recipients_display_name ON $TABLE_RECIPIENTS (display_name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX recipients_account_uuid ON $TABLE_RECIPIENTS (account_uuid)")

        db.execSQL(
            "CREATE TABLE $TABLE_SYNC_STATE (" +
                "account_uuid TEXT NOT NULL," +
                "key TEXT NOT NULL," +
                "value TEXT," +
                "PRIMARY KEY (account_uuid, key)" +
                ")",
        )
    }

    /**
     * Rebuilt rather than migrated: everything here is derived, and a scan restores it.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RECIPIENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SYNC_STATE")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }
}
