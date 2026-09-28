package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import com.fsck.k9.mailstore.MigrationsHelper

/**
 * Migration to version 94.
 *
 * Turns on synchronization of the account's Sent folder, as a newly chosen Sent folder now gets. Suggestions of who
 * to write to are built from the sent mail on the device, and a Sent folder that is never synchronized leaves them
 * with nothing to go on.
 *
 * Done once, here, rather than every time the special folders are checked, so a user who then turns it off in the
 * folder's settings keeps it off.
 */
internal class MigrationTo94(private val db: SQLiteDatabase, private val migrationsHelper: MigrationsHelper) {

    fun enableSentFolderSync() {
        val sentFolderId = migrationsHelper.account.sentFolderId ?: return

        db.update("folders", contentValuesOf("sync_enabled" to true), "id = ?", arrayOf(sentFolderId.toString()))
    }
}
