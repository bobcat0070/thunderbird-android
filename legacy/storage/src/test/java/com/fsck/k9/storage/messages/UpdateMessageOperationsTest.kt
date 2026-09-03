package com.fsck.k9.storage.messages

import android.database.sqlite.SQLiteDatabase
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.storage.RobolectricTest
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import org.junit.After
import org.junit.Before
import org.junit.Test

class UpdateMessageOperationsTest : RobolectricTest() {
    private lateinit var sqliteDatabase: SQLiteDatabase
    private lateinit var updateMessageOperations: UpdateMessageOperations

    @Before
    fun setUp() {
        sqliteDatabase = createDatabase()
        val lockableDatabase = createLockableDatabaseMock(sqliteDatabase)
        updateMessageOperations = UpdateMessageOperations(lockableDatabase)
    }

    @After
    fun tearDown() {
        sqliteDatabase.close()
    }

    @Test
    fun `mark message as new`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", newMessage = false)

        updateMessageOperations.setNewMessageState(folderId = 1, messageServerId = "uid1", newMessage = true)

        val messages = sqliteDatabase.readMessages()
        assertThat(messages).hasSize(1)

        val message = messages.first()
        assertThat(message.newMessage).isEqualTo(1)
    }

    @Test
    fun `mark message as not new`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", newMessage = true)

        updateMessageOperations.setNewMessageState(folderId = 1, messageServerId = "uid1", newMessage = false)

        val messages = sqliteDatabase.readMessages()
        assertThat(messages).hasSize(1)

        val message = messages.first()
        assertThat(message.newMessage).isEqualTo(0)
    }

    @Test
    fun `clear new message state`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", newMessage = true)
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", newMessage = false)

        updateMessageOperations.clearNewMessageState()

        val messages = sqliteDatabase.readMessages()
        assertThat(messages).hasSize(2)

        for (message in messages) {
            assertThat(message.newMessage).isEqualTo(0)
        }
    }

    @Test
    fun `teaching a sender should re-classify their stored messages`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("sam@example.com"))

        val updated = reclassify(RuleScope.SENDER, "sam@example.com")

        assertThat(updated).isEqualTo(1)
        assertThat(sqliteDatabase.readMessages().first().classification).isEqualTo("HUMAN")
        assertThat(sqliteDatabase.readMessages().first().classificationSignal).isEqualTo("USER_OVERRIDE")
    }

    @Test
    fun `a sender with a display name should still match`() {
        // The common case: the packed column is `address;<0x01>Personal`, not a bare address.
        sqliteDatabase.createMessage(
            folderId = 1,
            uid = "uid1",
            senderList = packed("sam@example.com", personal = "Sam Okafor"),
        )

        val updated = reclassify(RuleScope.SENDER, "sam@example.com")

        assertThat(updated).isEqualTo(1)
    }

    @Test
    fun `a different sender should be left alone`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("other@example.com"))

        val updated = reclassify(RuleScope.SENDER, "sam@example.com")

        assertThat(updated).isEqualTo(0)
        assertThat(sqliteDatabase.readMessages().first().classification).isNull()
    }

    @Test
    fun `a sender rule should not match an address that merely starts with it`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("sam@example.com.co"))

        val updated = reclassify(RuleScope.SENDER, "sam@example.com")

        assertThat(updated).isEqualTo(0)
    }

    @Test
    fun `sender matching should ignore case`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("Sam@Example.COM"))

        val updated = reclassify(RuleScope.SENDER, "sam@example.com")

        assertThat(updated).isEqualTo(1)
    }

    @Test
    fun `a domain rule should match every address at that domain`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("bounce-1@mail.shop.com"))
        sqliteDatabase.createMessage(
            folderId = 1,
            uid = "uid2",
            senderList = packed("bounce-2@mail.shop.com", personal = "Shop"),
        )
        sqliteDatabase.createMessage(folderId = 1, uid = "uid3", senderList = packed("sam@example.com"))

        val updated = reclassify(RuleScope.DOMAIN, "mail.shop.com", MessageClass.NEWSLETTER)

        assertThat(updated).isEqualTo(2)
    }

    @Test
    fun `a domain rule should not match a domain that ends with it`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("sam@notshop.com"))

        val updated = reclassify(RuleScope.DOMAIN, "shop.com", MessageClass.NEWSLETTER)

        assertThat(updated).isEqualTo(0)
    }

    @Test
    fun `an underscore in an address should not act as a wildcard`() {
        // Unescaped, LIKE would treat the underscore as "any character" and re-classify a stranger.
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("samXdoe@example.com"))

        val updated = reclassify(RuleScope.SENDER, "sam_doe@example.com")

        assertThat(updated).isEqualTo(0)
    }

    @Test
    fun `a wildcard in a rule should not re-classify the whole mailbox`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", senderList = packed("sam@example.com"))
        sqliteDatabase.createMessage(folderId = 1, uid = "uid2", senderList = packed("other@example.com"))

        val updated = reclassify(RuleScope.SENDER, "%@example.com")

        assertThat(updated).isEqualTo(0)
    }

    private fun reclassify(
        scope: RuleScope,
        pattern: String,
        messageClass: MessageClass = MessageClass.HUMAN,
    ): Int {
        return updateMessageOperations.setClassificationForSender(
            scope = scope,
            pattern = pattern,
            messageClass = messageClass,
            signal = "USER_OVERRIDE",
            classifierVersion = 1,
        )
    }

    /**
     * Mirrors how `Address.pack` writes the column: the address, then the personal name after a `;` and a
     * `U+0001`, if there is one.
     */
    private fun packed(address: String, personal: String? = null): String {
        return if (personal == null) address else "$address;\u0001$personal"
    }
}
