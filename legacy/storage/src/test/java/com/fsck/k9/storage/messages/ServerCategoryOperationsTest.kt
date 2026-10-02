package com.fsck.k9.storage.messages

import android.database.sqlite.SQLiteDatabase
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.storage.RobolectricTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class ServerCategoryOperationsTest : RobolectricTest() {
    private lateinit var sqliteDatabase: SQLiteDatabase
    private lateinit var testSubject: ServerCategoryOperations

    @Before
    fun setUp() {
        sqliteDatabase = createDatabase()
        testSubject = ServerCategoryOperations(createLockableDatabaseMock(sqliteDatabase))
    }

    @After
    fun tearDown() {
        sqliteDatabase.close()
    }

    @Test
    fun `categories set on a message should be read back`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1")

        testSubject.setMessageServerCategories(folderId = 1, messageServerId = "uid1", listOf("Red", "Project X"))

        val result = testSubject.getMessageServerCategories(folderId = 1, messageServerId = "uid1")
        assertThat(result).isEqualTo(listOf("Red", "Project X"))
    }

    @Test
    fun `setting categories should not touch the same server id in another folder`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1")
        sqliteDatabase.createMessage(folderId = 2, uid = "uid1")

        testSubject.setMessageServerCategories(folderId = 1, messageServerId = "uid1", listOf("Red"))

        assertThat(testSubject.getMessageServerCategories(folderId = 2, messageServerId = "uid1")).isEmpty()
    }

    @Test
    fun `a message without categories should have none`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1")

        assertThat(testSubject.getMessageServerCategories(folderId = 1, messageServerId = "uid1")).isEmpty()
        assertThat(testSubject.getMessageServerCategories(folderId = 1, messageServerId = "missing")).isEmpty()
    }

    @Test
    fun `removing every category should clear the column`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", serverCategories = "Red\nBlue")

        testSubject.setMessageServerCategories(folderId = 1, messageServerId = "uid1", emptyList())

        assertThat(sqliteDatabase.readMessages().single().serverCategories).isNull()
    }

    @Test
    fun `categories should be set on every given message`() {
        val messageId1 = sqliteDatabase.createMessage(folderId = 1, uid = "uid1", serverCategories = "Old")
        val messageId2 = sqliteDatabase.createMessage(folderId = 1, uid = "uid2")
        sqliteDatabase.createMessage(folderId = 1, uid = "uid3", serverCategories = "Untouched")

        testSubject.setServerCategories(listOf(messageId1, messageId2), listOf("Red", "Blue"))

        val categories = sqliteDatabase.readMessages().associate { it.uid to it.serverCategories }
        assertThat(categories).isEqualTo(mapOf("uid1" to "Red\nBlue", "uid2" to "Red\nBlue", "uid3" to "Untouched"))
    }

    @Test
    fun `the categories in use should be listed once each in alphabetical order`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", serverCategories = "Red\nProject X")
        sqliteDatabase.createMessage(folderId = 1, uid = "uid2", serverCategories = "blue")
        sqliteDatabase.createMessage(folderId = 2, uid = "uid3", serverCategories = "Red")
        sqliteDatabase.createMessage(folderId = 2, uid = "uid4")

        assertThat(testSubject.getServerCategories()).isEqualTo(listOf("blue", "Project X", "Red"))
    }

    @Test
    fun `categories only found on deleted messages should not be listed`() {
        sqliteDatabase.createMessage(folderId = 1, uid = "uid1", serverCategories = "Gone", deleted = true)
        sqliteDatabase.createMessage(folderId = 1, uid = "uid2", serverCategories = "Kept")

        assertThat(testSubject.getServerCategories()).isEqualTo(listOf("Kept"))
    }
}
