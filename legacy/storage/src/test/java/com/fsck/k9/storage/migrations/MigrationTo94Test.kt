package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import com.fsck.k9.mailstore.MigrationsHelper
import kotlin.test.Test
import net.thunderbird.core.android.account.LegacyAccountDto
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import net.thunderbird.feature.account.AccountIdFactory

@RunWith(RobolectricTestRunner::class)
class MigrationTo94Test {
    private val database = createDatabaseVersion93()
    private val account = LegacyAccountDto(id = AccountIdFactory.of("00000000-0000-4000-8000-000000000001"))
    private val migration = MigrationTo94(database, createMigrationsHelper(account))

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `should turn on synchronization of the sent folder`() {
        val inboxId = database.createFolder(name = "Inbox", syncEnabled = true)
        val sentId = database.createFolder(name = "Sent", syncEnabled = false)
        database.createFolder(name = "Archive", syncEnabled = false)
        account.inboxFolderId = inboxId
        account.sentFolderId = sentId

        migration.enableSentFolderSync()

        assertThat(database.readSyncEnabled()).containsExactlyInAnyOrder(
            "Inbox" to true,
            "Sent" to true,
            "Archive" to false,
        )
    }

    @Test
    fun `should leave folders alone when the account has no sent folder`() {
        database.createFolder(name = "Inbox", syncEnabled = true)
        database.createFolder(name = "Other", syncEnabled = false)

        migration.enableSentFolderSync()

        assertThat(database.readSyncEnabled()).containsExactlyInAnyOrder(
            "Inbox" to true,
            "Other" to false,
        )
    }

    private fun createMigrationsHelper(account: LegacyAccountDto): MigrationsHelper {
        return object : MigrationsHelper {
            override fun getAccount(): LegacyAccountDto = account

            override fun saveAccount() {
                throw UnsupportedOperationException("not implemented")
            }
        }
    }

    private fun createDatabaseVersion93(): SQLiteDatabase {
        return SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, name TEXT, sync_enabled INTEGER DEFAULT 0)")
        }
    }

    private fun SQLiteDatabase.createFolder(name: String, syncEnabled: Boolean): Long {
        execSQL("INSERT INTO folders (name, sync_enabled) VALUES (?, ?)", arrayOf<Any>(name, if (syncEnabled) 1 else 0))

        return rawQuery("SELECT last_insert_rowid()", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private fun SQLiteDatabase.readSyncEnabled(): List<Pair<String, Boolean>> {
        return rawQuery("SELECT name, sync_enabled FROM folders", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to (cursor.getInt(1) == 1))
                }
            }
        }
    }
}
