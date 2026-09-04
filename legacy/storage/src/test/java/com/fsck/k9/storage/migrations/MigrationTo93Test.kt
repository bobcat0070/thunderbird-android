package com.fsck.k9.storage.migrations

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.fsck.k9.storage.messages.createMessage
import com.fsck.k9.storage.messages.readMessages
import kotlin.test.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTo93Test {
    private val database = createMessagesTableVersion91(
        "classification TEXT",
        "classification_signal TEXT",
        "classifier_version INTEGER DEFAULT 0",
    )
    private val migration = MigrationTo93(database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `should add the sender authentication column`() {
        migration.addSenderAuthenticationColumn()

        assertThat(database.messageColumnNames()).contains("sender_authenticated")
    }

    @Test
    fun `should treat existing messages as unauthenticated`() {
        // The security property this migration has to get right. The flag decides whether a sender's brand
        // logo may be shown, and nobody checked DMARC for mail that was stored before the column existed.
        // Defaulting to authenticated would hand a verified-brand indicator to every message already on the
        // device, including anything spoofed.
        database.createMessage(folderId = 1, uid = "uid1")

        migration.addSenderAuthenticationColumn()

        assertThat(database.readMessages().single().senderAuthenticated).isEqualTo(0)
    }

    @Test
    fun `should leave existing messages in place`() {
        database.createMessage(folderId = 1, uid = "uid1", subject = "Message")

        migration.addSenderAuthenticationColumn()

        val messages = database.readMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages.single().subject).isEqualTo("Message")
    }

    @Test
    fun `should not fail when the column already exists`() {
        migration.addSenderAuthenticationColumn()

        migration.addSenderAuthenticationColumn()

        assertThat(database.messageColumnNames()).contains("sender_authenticated")
    }

    @Test
    fun `should not disturb a flag written between runs`() {
        migration.addSenderAuthenticationColumn()
        database.execSQL("INSERT INTO messages (uid, sender_authenticated) VALUES ('uid1', 1)")

        migration.addSenderAuthenticationColumn()

        assertThat(database.readMessages().single().senderAuthenticated).isEqualTo(1)
    }
}
