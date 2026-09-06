package com.fsck.k9.mailstore

import com.fsck.k9.mail.Address
import java.util.Date
import net.thunderbird.feature.mail.message.classification.api.MessageClassification

data class MessageDetails(
    val date: MessageDate,
    val from: List<Address>,
    val sender: Address?,
    val replyTo: List<Address>,
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,

    /**
     * What kind of mail this was decided to be and why, or `null` when nothing decided.
     */
    val classification: MessageClassification?,
)

sealed interface MessageDate {
    data class ValidDate(val date: Date) : MessageDate

    data class InvalidDate(val dateHeader: String) : MessageDate

    object MissingDate : MessageDate
}
