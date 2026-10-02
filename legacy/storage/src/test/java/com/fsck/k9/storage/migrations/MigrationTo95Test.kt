package com.fsck.k9.storage.migrations

import android.content.ContentValues
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.storage.messages.createMessage
import com.fsck.k9.storage.messages.readMessages
import kotlin.test.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTo95Test {
    private val database = createMessagesTableVersion91(
        "classification TEXT",
        "classification_signal TEXT",
        "classifier_version INTEGER DEFAULT 0",
        "sender_authenticated INTEGER DEFAULT 0",
    ).apply {
        execSQL("CREATE TABLE message_parts (id INTEGER PRIMARY KEY, header BLOB)")
    }
    private val migration = MigrationTo95(database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `should add the importance and server categories columns`() {
        migration.addImportanceAndServerCategoriesColumns()

        val columns = database.messageColumnNames()
        assertThat(columns).contains("importance")
        assertThat(columns).contains("server_categories")
    }

    @Test
    fun `should leave existing messages in place`() {
        createMessageWithHeader(uid = "uid1", subject = "Message", header = "Subject: Message\r\n")

        migration.addImportanceAndServerCategoriesColumns()

        val messages = database.readMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages.single().subject).isEqualTo("Message")
    }

    @Test
    fun `a message that states no importance should be of normal importance and have no categories`() {
        createMessageWithHeader(uid = "uid1", header = "Subject: Message\r\n")

        migration.addImportanceAndServerCategoriesColumns()

        val message = database.readMessages().single()
        assertThat(message.importance).isEqualTo(0)
        assertThat(message.serverCategories).isNull()
    }

    @Test
    fun `should read the importance of stored messages from their headers`() {
        createMessageWithHeader(uid = "high", header = "Subject: A\r\nImportance: high\r\nX-Priority: 1\r\n")
        createMessageWithHeader(uid = "low", header = "Subject: B\r\nimportance: Low\r\n")
        createMessageWithHeader(uid = "priority", header = "X-Priority: 1 (Highest)\r\nSubject: C\r\n")
        createMessageWithHeader(uid = "lowest", header = "Subject: D\r\nX-Priority: 5\r\n")

        migration.addImportanceAndServerCategoriesColumns()

        assertThat(importanceByUid()).isEqualTo(mapOf("high" to 1, "low" to -1, "priority" to 1, "lowest" to -1))
    }

    @Test
    fun `should not take another header that mentions importance for the importance`() {
        // The stored headers are searched as text, so a subject or another header saying "importance: high"
        // is found too. Only a header field of that name counts.
        createMessageWithHeader(uid = "subject", header = "Subject: Re: importance: high\r\n")
        createMessageWithHeader(uid = "other", header = "X-Importance: high\r\nSubject: A\r\n")
        createMessageWithHeader(uid = "normal", header = "Importance: normal\r\nX-Priority: 3\r\n")

        migration.addImportanceAndServerCategoriesColumns()

        assertThat(importanceByUid()).isEqualTo(mapOf("subject" to 0, "other" to 0, "normal" to 0))
    }

    @Test
    fun `a message whose headers were never stored should be of normal importance`() {
        database.createMessage(folderId = 1, uid = "uid1", messagePartId = 99)

        migration.addImportanceAndServerCategoriesColumns()

        assertThat(database.readMessages().single().importance).isEqualTo(0)
    }

    @Test
    fun `should not fail when the columns already exist`() {
        migration.addImportanceAndServerCategoriesColumns()

        migration.addImportanceAndServerCategoriesColumns()

        val columns = database.messageColumnNames()
        assertThat(columns).contains("importance")
        assertThat(columns).contains("server_categories")
    }

    @Test
    fun `should not disturb what was written between runs`() {
        migration.addImportanceAndServerCategoriesColumns()
        database.execSQL(
            "INSERT INTO messages (uid, importance, server_categories) VALUES ('uid1', 1, 'Red category')",
        )

        migration.addImportanceAndServerCategoriesColumns()

        val message = database.readMessages().single()
        assertThat(message.importance).isEqualTo(1)
        assertThat(message.serverCategories).isEqualTo("Red category")
    }

    private fun createMessageWithHeader(uid: String, header: String, subject: String = "") {
        val messagePartId = database.insert(
            "message_parts",
            null,
            ContentValues().apply { put("header", header.toByteArray()) },
        )

        database.createMessage(folderId = 1, uid = uid, subject = subject, messagePartId = messagePartId)
    }

    private fun importanceByUid(): Map<String?, Int?> {
        return database.readMessages().associate { it.uid to it.importance }
    }
}
