package com.fsck.k9.storage.messages

import android.content.ContentValues
import android.database.Cursor
import app.k9mail.core.android.common.database.getIntOrNull
import app.k9mail.core.android.common.database.getStringOrNull

/**
 * What was decided about a message, as opposed to what the message says.
 *
 * Copying or moving a message produces the same message in another folder, so these travel with it. Left
 * behind, they fall back to the column defaults, and a moved message becomes uncategorised and - worse -
 * unauthenticated, losing the DMARC pass that decides whether its sender's logo can be trusted.
 *
 * Kept in one place because the copy and move paths are separate code that has to agree; the last four
 * columns being absent from both is exactly the kind of omission that goes unnoticed.
 */
internal val CLASSIFICATION_COLUMNS = arrayOf(
    "classification",
    "classification_signal",
    "classifier_version",
    "sender_authenticated",
)

/**
 * Copies [CLASSIFICATION_COLUMNS] across from a row that selected them.
 */
internal fun ContentValues.putClassificationFrom(cursor: Cursor) {
    put("classification", cursor.getStringOrNull("classification"))
    put("classification_signal", cursor.getStringOrNull("classification_signal"))
    put("classifier_version", cursor.getIntOrNull("classifier_version"))
    put("sender_authenticated", cursor.getIntOrNull("sender_authenticated"))
}
