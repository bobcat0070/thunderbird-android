package com.fsck.k9.storage.migrations

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
class MigrationTo92Test {
    private val database = createMessagesTableVersion91()
    private val migration = MigrationTo92(database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `should add the classification columns`() {
        migration.addClassificationColumns()

        val columns = database.messageColumnNames()
        assertThat(columns).contains("classification")
        assertThat(columns).contains("classification_signal")
        assertThat(columns).contains("classifier_version")
    }

    @Test
    fun `should leave existing messages in place`() {
        // The upgrade runs against a mailbox somebody has, unattended and once. Losing a row here is not
        // something they can undo.
        database.createMessage(folderId = 1, uid = "uid1", subject = "Message")

        migration.addClassificationColumns()

        val messages = database.readMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages.single().subject).isEqualTo("Message")
    }

    @Test
    fun `should leave existing messages unclassified`() {
        // Deciding needs headers that are only in hand while a message is being saved, so there is nothing
        // honest to write here. Null says "not yet decided", which is what the re-classification pass looks
        // for; guessing a class during an upgrade would instead look like a decision that had been made.
        database.createMessage(folderId = 1, uid = "uid1")

        migration.addClassificationColumns()

        val message = database.readMessages().single()
        assertThat(message.classification).isNull()
        assertThat(message.classificationSignal).isNull()
    }

    @Test
    fun `should treat existing messages as older than any classifier`() {
        // Zero, not null: the re-classification pass compares against this, and a version of zero is what
        // makes mail that predates classification the first thing it picks up.
        database.createMessage(folderId = 1, uid = "uid1")

        migration.addClassificationColumns()

        assertThat(database.readMessages().single().classifierVersion).isEqualTo(0)
    }

    @Test
    fun `should not fail when the columns already exist`() {
        // Upgrades can be interrupted and replayed, and a migration that throws the second time round leaves
        // a database nothing can open.
        migration.addClassificationColumns()

        migration.addClassificationColumns()

        assertThat(database.messageColumnNames()).contains("classification")
    }

    @Test
    fun `should not disturb a classification written between runs`() {
        migration.addClassificationColumns()
        database.execSQL("INSERT INTO messages (uid, classification) VALUES ('uid1', 'HUMAN')")

        migration.addClassificationColumns()

        assertThat(database.readMessages().single().classification).isEqualTo("HUMAN")
    }
}
