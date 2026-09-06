package com.fsck.k9.storage.messages

import android.database.sqlite.SQLiteDatabase
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.mail.Address
import com.fsck.k9.storage.RobolectricTest
import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.MessageClassification
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val CURRENT_VERSION = 3

class ReclassifyMessageOperationsTest : RobolectricTest() {
    private lateinit var sqliteDatabase: SQLiteDatabase
    private lateinit var reclassifyMessageOperations: ReclassifyMessageOperations

    @Before
    fun setUp() {
        sqliteDatabase = createDatabase()
        reclassifyMessageOperations = ReclassifyMessageOperations(createLockableDatabaseMock(sqliteDatabase))
    }

    @After
    fun tearDown() {
        sqliteDatabase.close()
    }

    @Test
    fun `a message classified by older rules should be offered`() {
        storeMessage(classifierVersion = CURRENT_VERSION - 1)

        assertThat(toReclassify()).hasSize(1)
    }

    @Test
    fun `a message classified by the current rules should not be offered`() {
        storeMessage(classifierVersion = CURRENT_VERSION)

        assertThat(toReclassify()).isEmpty()
    }

    @Test
    fun `a message from before the column existed should be offered`() {
        // Null is neither less than nor greater than anything in SQL, so without IFNULL the oldest mail in
        // the mailbox would be exactly the mail that never caught up. Set explicitly, because a row inserted
        // today picks up the column default rather than the null a row from before the migration holds.
        val messageId = storeMessage(classifierVersion = null)
        sqliteDatabase.execSQL("UPDATE messages SET classifier_version = NULL WHERE id = $messageId")

        assertThat(toReclassify()).hasSize(1)
    }

    @Test
    fun `deleted and placeholder messages should not be offered`() {
        // A placeholder row stands in for a message known only by its position in a thread; there is nothing
        // to classify, and a deleted message is not shown.
        storeMessage(classifierVersion = null, deleted = true)
        storeMessage(classifierVersion = null, empty = true)

        assertThat(toReclassify()).isEmpty()
    }

    @Test
    fun `a message with no stored headers should not be offered`() {
        // It could never be classified again, so returning it would mean handing back the same batch forever.
        val messagePartId = sqliteDatabase.createMessagePart(header = null)
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", messagePartId = messagePartId)

        assertThat(toReclassify()).isEmpty()
    }

    @Test
    fun `evidence should carry the classification headers and the addresses`() {
        storeMessage(
            classifierVersion = null,
            senderList = packed("news@shop.example"),
            toList = packed("sam@example.com"),
            ccList = packed("kim@example.com", "lee@example.com"),
            header = """
                |From: Shop <news@shop.example>
                |Subject: Sale
                |List-Unsubscribe: <https://shop.example/unsub>
                |Precedence: bulk
                |
            """.trimMargin(),
        )

        val evidence = toReclassify().single().evidence

        assertThat(evidence.fromAddress).isEqualTo("news@shop.example")
        assertThat(evidence.recipientCount).isEqualTo(3)
        assertThat(evidence.firstHeader("List-Unsubscribe")).isEqualTo("<https://shop.example/unsub>")
        assertThat(evidence.firstHeader("Precedence")).isEqualTo("bulk")
    }

    @Test
    fun `evidence should not carry headers the classifier does not read`() {
        // This runs over every message in the mailbox, and a stored header block is mostly routing history.
        storeMessage(
            classifierVersion = null,
            header = "Subject: Sale\nReceived: from mx.shop.example\nList-Id: <sale.shop.example>\n",
        )

        val evidence = toReclassify().single().evidence

        assertThat(evidence.headers.keys.toList()).doesNotContain("received")
        assertThat(evidence.firstHeader("List-Id")).isEqualTo("<sale.shop.example>")
    }

    @Test
    fun `a header the message does not carry should be present and empty`() {
        // The same shape the save path produces, so a stored message is judged on the same evidence as one
        // arriving now rather than on a map that happens to be missing keys.
        storeMessage(classifierVersion = null)

        assertThat(toReclassify().single().evidence.headers["precedence"]).isNotNull().isEmpty()
    }

    @Test
    fun `a repeated header should be kept in full`() {
        storeMessage(
            classifierVersion = null,
            header = "List-Unsubscribe: <mailto:a@shop.example>\nList-Unsubscribe: <https://shop.example/b>\n",
        )

        assertThat(toReclassify().single().evidence.headers["list-unsubscribe"]).isNotNull().containsExactly(
            "<mailto:a@shop.example>",
            "<https://shop.example/b>",
        )
    }

    @Test
    fun `the limit should bound a batch`() {
        repeat(times = 5) { storeMessage(classifierVersion = null) }

        val page = reclassifyMessageOperations.getMessagesToReclassify(CURRENT_VERSION, 2, afterMessageId = 0)

        assertThat(page).hasSize(2)
    }

    @Test
    fun `writing a verdict should record the class, the signal and the version`() {
        val messageId = storeMessage(classifierVersion = null)

        val updated = reclassifyMessageOperations.setClassifications(
            classifications = mapOf(
                messageId to MessageClassification(MessageClass.NEWSLETTER, ClassificationSignal.BULK_HEADER),
            ),
            classifierVersion = CURRENT_VERSION,
        )

        assertThat(updated).isEqualTo(1)
        val message = sqliteDatabase.readMessages().single()
        assertThat(message.classification).isEqualTo("NEWSLETTER")
        assertThat(message.classificationSignal).isEqualTo("BULK_HEADER")
        assertThat(message.classifierVersion).isEqualTo(CURRENT_VERSION)
    }

    @Test
    fun `a message that has been written should not be offered again`() {
        // What makes the batching terminate: each pass has to shrink the work left.
        val messageId = storeMessage(classifierVersion = null)

        reclassifyMessageOperations.setClassifications(
            classifications = mapOf(messageId to MessageClassification.UNKNOWN),
            classifierVersion = CURRENT_VERSION,
        )

        assertThat(toReclassify()).isEmpty()
    }

    @Test
    fun `writing nothing should touch nothing`() {
        storeMessage(classifierVersion = null)

        assertThat(reclassifyMessageOperations.setClassifications(emptyMap(), CURRENT_VERSION)).isEqualTo(0)
        assertThat(sqliteDatabase.readMessages().single().classification).isNull()
    }

    private fun packed(vararg addresses: String): String =
        Address.pack(addresses.map { Address(it) }.toTypedArray())

    @Test
    fun `paging by id should return only what follows`() {
        // How a forced pass walks the mailbox: every row it writes still matches the version filter, so it
        // cannot page by version.
        val first = storeMessage(classifierVersion = null)
        val second = storeMessage(classifierVersion = null)

        val page = reclassifyMessageOperations.getMessagesToReclassify(
            CURRENT_VERSION,
            limit = 100,
            afterMessageId = first,
        )

        assertThat(page.map { it.messageId }).isEqualTo(listOf(second))
    }

    private fun toReclassify() =
        reclassifyMessageOperations.getMessagesToReclassify(CURRENT_VERSION, limit = 100, afterMessageId = 0)

    @Suppress("LongParameterList")
    private fun storeMessage(
        classifierVersion: Int?,
        senderList: String = packed("news@shop.example"),
        toList: String = "",
        ccList: String = "",
        header: String = "Subject: Sale\n",
        deleted: Boolean = false,
        empty: Boolean = false,
    ): Long {
        val messagePartId = sqliteDatabase.createMessagePart(header = header)

        return sqliteDatabase.createMessage(
            folderId = 1,
            uid = "uid$messagePartId",
            messagePartId = messagePartId,
            senderList = senderList,
            toList = toList,
            ccList = ccList,
            deleted = deleted,
            empty = empty,
            classifierVersion = classifierVersion,
        )
    }
}
