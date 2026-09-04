package com.fsck.k9.notification

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mail.Address

internal data class NotificationContent(
    val messageReference: MessageReference,
    val sender: Address,
    val subject: String,
    val preview: CharSequence,
    val summary: CharSequence,

    /**
     * Whether the receiving server reported that this message passed DMARC. Carried this far because the
     * sender's brand logo may only be shown for mail that did, and the notification is the one place the
     * sender is presented before the reader has opened anything.
     */
    val isSenderAuthenticated: Boolean = false,
)
