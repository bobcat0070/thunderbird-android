package com.fsck.k9.storage.messages

import android.content.ContentValues
import app.k9mail.legacy.mailstore.ServerCategoriesColumn
import com.fsck.k9.mailstore.LockableDatabase

/**
 * Reads and writes the categories the server keeps on a message, such as the ones assigned in Outlook.
 */
internal class ServerCategoryOperations(private val lockableDatabase: LockableDatabase) {

    fun getMessageServerCategories(folderId: Long, messageServerId: String): List<String> {
        return lockableDatabase.execute(false) { database ->
            database.query(
                "messages",
                arrayOf("server_categories"),
                "folder_id = ? AND uid = ?",
                arrayOf(folderId.toString(), messageServerId),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) ServerCategoriesColumn.decode(cursor.getString(0)) else emptyList()
            }
        }
    }

    fun setMessageServerCategories(folderId: Long, messageServerId: String, categories: List<String>) {
        lockableDatabase.execute(false) { database ->
            database.update(
                "messages",
                categories.toContentValues(),
                "folder_id = ? AND uid = ?",
                arrayOf(folderId.toString(), messageServerId),
            )
        }
    }

    fun setServerCategories(messageIds: Collection<Long>, categories: List<String>) {
        require(messageIds.isNotEmpty()) { "'messageIds' must not be empty" }

        val contentValues = categories.toContentValues()

        lockableDatabase.execute(true) { database ->
            performChunkedOperation(
                arguments = messageIds,
                argumentTransformation = Long::toString,
            ) { selectionSet, selectionArguments ->
                database.update("messages", contentValues, "id $selectionSet", selectionArguments)
            }
        }
    }

    /**
     * Every category in use on the messages of this account, in alphabetical order.
     *
     * The app has no access to the list of categories the mailbox defines, so the ones it can offer are the ones
     * it has seen.
     */
    fun getServerCategories(): List<String> {
        return lockableDatabase.execute(false) { database ->
            database.rawQuery(
                "SELECT DISTINCT server_categories FROM messages " +
                    "WHERE server_categories IS NOT NULL AND empty = 0 AND deleted = 0",
                null,
            ).use { cursor ->
                val categories = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
                while (cursor.moveToNext()) {
                    categories += ServerCategoriesColumn.decode(cursor.getString(0))
                }

                categories.toList()
            }
        }
    }

    private fun List<String>.toContentValues() = ContentValues().apply {
        put("server_categories", ServerCategoriesColumn.encode(this@toContentValues))
    }
}
