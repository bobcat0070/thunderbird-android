package com.fsck.k9.storage.messages

import com.fsck.k9.mail.MessageImportance

private const val IMPORTANCE_LOW = -1
private const val IMPORTANCE_NORMAL = 0
private const val IMPORTANCE_HIGH = 1

/**
 * The value of the `importance` column. Normal is 0, which is also the column's default, so a message stored
 * before the column existed reads as normal.
 */
internal fun MessageImportance.toDatabaseValue(): Int = when (this) {
    MessageImportance.LOW -> IMPORTANCE_LOW
    MessageImportance.NORMAL -> IMPORTANCE_NORMAL
    MessageImportance.HIGH -> IMPORTANCE_HIGH
}

internal fun importanceFromDatabaseValue(value: Int): MessageImportance = when {
    value < IMPORTANCE_NORMAL -> MessageImportance.LOW
    value > IMPORTANCE_NORMAL -> MessageImportance.HIGH
    else -> MessageImportance.NORMAL
}
