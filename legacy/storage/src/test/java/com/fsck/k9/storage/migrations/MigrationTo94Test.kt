package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import kotlin.test.Test
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.account.storage.profile.AvatarDto
import net.thunderbird.feature.account.storage.profile.AvatarTypeDto
import net.thunderbird.feature.account.storage.profile.ProfileDto
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTo94Test {
    private val database = createDatabaseVersion93()
    private val migrationsHelper = FakeMigrationsHelper(createAccount())
    private val migration = MigrationTo94(database, migrationsHelper)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `should turn on synchronization of the sent folder`() {
        val inboxId = database.createFolder(name = "Inbox", syncEnabled = true)
        val sentId = database.createFolder(name = "Sent", syncEnabled = false)
        database.createFolder(name = "Archive", syncEnabled = false)
        migrationsHelper.updateAccount { it.copy(inboxFolderId = inboxId, sentFolderId = sentId) }

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

    private fun createAccount(): LegacyAccount {
        val accountId = AccountIdFactory.of("00000000-0000-4000-8000-000000000001")
        val serverSettings = ServerSettings(
            type = "dummy",
            host = "",
            port = -1,
            connectionSecurity = ConnectionSecurity.NONE,
            authenticationType = AuthType.PLAIN,
            username = "",
            password = "",
            clientCertificateAlias = null,
        )
        return LegacyAccount(
            id = accountId,
            name = "Account",
            email = "account@example.org",
            profile = ProfileDto(
                id = accountId,
                name = "Account",
                color = 0,
                avatar = AvatarDto(
                    accountId = accountId,
                    avatarType = AvatarTypeDto.MONOGRAM,
                    avatarMonogram = null,
                    avatarImageUri = null,
                    avatarIconName = null,
                ),
            ),
            incomingServerSettings = serverSettings,
            outgoingServerSettings = serverSettings,
            identities = listOf(Identity(email = "account@example.org")),
        )
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
