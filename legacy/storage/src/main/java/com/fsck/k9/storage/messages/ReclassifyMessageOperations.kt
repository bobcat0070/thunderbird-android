package com.fsck.k9.storage.messages

import android.content.ContentValues
import app.k9mail.legacy.mailstore.StoredClassificationEvidence
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.MimeHeader
import com.fsck.k9.mail.message.MessageHeaderParser
import com.fsck.k9.mailstore.LockableDatabase
import net.thunderbird.feature.mail.message.classification.api.CLASSIFICATION_HEADERS
import net.thunderbird.feature.mail.message.classification.api.MessageClassification
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence

/**
 * Messages whose classification predates the current rules, oldest row first.
 *
 * `IFNULL` because rows written before the column existed hold null, and null compares as neither less nor
 * greater in SQL - without it, exactly the oldest mail would be the mail that never caught up. `CAST` because
 * wrapping the column in an expression drops its integer affinity, and an integer compared against the bound
 * string is then always the smaller of the two - which would return every message, always.
 *
 * A message with no stored header block cannot be classified again and is left out, rather than being
 * returned and handed back unchanged on every pass.
 */
private const val TO_RECLASSIFY_QUERY =
    "SELECT messages.id, messages.sender_list, messages.to_list, messages.cc_list, message_parts.header" +
        " FROM messages" +
        " JOIN message_parts ON (messages.message_part_id = message_parts.id)" +
        " WHERE messages.deleted = 0 AND messages.empty = 0" +
        " AND IFNULL(messages.classifier_version, 0) < CAST(? AS INTEGER)" +
        " AND message_parts.header IS NOT NULL" +
        " AND messages.id > ?" +
        " ORDER BY messages.id" +
        " LIMIT ?"

private const val COLUMN_ID = 0
private const val COLUMN_SENDER_LIST = 1
private const val COLUMN_TO_LIST = 2
private const val COLUMN_CC_LIST = 3
private const val COLUMN_HEADER = 4

/**
 * Rebuilds classification evidence from mail that is already stored, and writes new verdicts back.
 *
 * The alternative was to leave stored mail on whatever the rules said when it arrived, which quietly means a
 * fix to the rules never reaches the mailbox the user actually looks at - only mail that has yet to arrive.
 * For an account synchronised once and then read for months, that is almost all of it.
 */
internal class ReclassifyMessageOperations(private val lockableDatabase: LockableDatabase) {

    fun getMessagesToReclassify(
        classifierVersion: Int,
        limit: Int,
        afterMessageId: Long,
    ): List<StoredClassificationEvidence> {
        return lockableDatabase.execute(false) { database ->
            database.rawQuery(
                TO_RECLASSIFY_QUERY,
                arrayOf(classifierVersion.toString(), afterMessageId.toString(), limit.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StoredClassificationEvidence(
                                messageId = cursor.getLong(COLUMN_ID),
                                evidence = MessageEvidence(
                                    headers = headersFrom(cursor.getBlob(COLUMN_HEADER)),
                                    fromAddress = firstAddressOf(cursor.getString(COLUMN_SENDER_LIST)),
                                    recipientCount = addressCountOf(cursor.getString(COLUMN_TO_LIST)) +
                                        addressCountOf(cursor.getString(COLUMN_CC_LIST)),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Written in one transaction so a mailbox is never left half on the old rules and half on the new after
     * an interrupted pass; the next run then either redoes the batch or does not need to.
     */
    fun setClassifications(classifications: Map<Long, MessageClassification>, classifierVersion: Int): Int {
        if (classifications.isEmpty()) return 0

        return lockableDatabase.execute(true) { database ->
            classifications.entries.sumOf { (messageId, classification) ->
                val values = ContentValues().apply {
                    put("classification", classification.messageClass.name)
                    put("classification_signal", classification.signal.name)
                    put("classifier_version", classifierVersion)
                }

                database.update("messages", values, "id = ?", arrayOf(messageId.toString()))
            }
        }
    }

    /**
     * Reads only the headers the classifier asks for. A stored header block is mostly routing history, and
     * this runs over every message in the mailbox.
     *
     * Shaped exactly as the save path shapes it - a key for every classification header, empty where the
     * message did not carry one - because the whole point is that a stored message is judged by the same
     * rules on the same evidence as one arriving now.
     */
    private fun headersFrom(headerBytes: ByteArray): Map<String, List<String>> {
        val wanted = CLASSIFICATION_HEADERS.mapTo(mutableSetOf()) { it.lowercase() }
        val parsed = MimeHeader()

        // The parser hands back the raw field, name and all; MimeHeader is what knows how to take the value
        // back out of one, including where a folded header continues onto the next line.
        MessageHeaderParser.parse(headerBytes.inputStream()) { name, raw ->
            if (name.lowercase() in wanted) {
                parsed.addRawHeader(name, raw)
            }
        }

        return CLASSIFICATION_HEADERS.associate { name -> name.lowercase() to parsed.getHeader(name).toList() }
    }

    private fun firstAddressOf(packed: String?): String? {
        return Address.unpack(packed).firstOrNull()?.address?.lowercase()
    }

    private fun addressCountOf(packed: String?): Int = Address.unpack(packed).size
}
