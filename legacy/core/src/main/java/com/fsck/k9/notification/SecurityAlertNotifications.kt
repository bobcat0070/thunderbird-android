package com.fsck.k9.notification

import com.fsck.k9.mail.Message
import com.fsck.k9.message.extractors.SecurityAlert
import com.fsck.k9.message.extractors.findSecurityAlert

/**
 * Headers only a discussion list puts on a message: mail on a list about passwords is not about the reader's own.
 */
private val LIST_HEADERS = listOf("List-Id", "List-Post")

/**
 * The security alert a message is, when it may be treated as one: it says so in its subject, it passed the sender
 * check of the account's own server, and it did not come through a mailing list.
 *
 * The sender check matters most here. Phishing dresses up as exactly this mail, and a security alert is let past the
 * reader's notification filters and labelled as one; a forged alert must get neither.
 *
 * @param isSenderAuthenticated whether the account's own server reported that the message passed DMARC.
 */
fun trustedSecurityAlert(message: Message, isSenderAuthenticated: Boolean): SecurityAlert? {
    if (!isSenderAuthenticated || LIST_HEADERS.any { message.getHeader(it).isNotEmpty() }) return null
    return findSecurityAlert(message.subject)
}
