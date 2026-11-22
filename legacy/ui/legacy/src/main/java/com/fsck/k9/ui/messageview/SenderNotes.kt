package com.fsck.k9.ui.messageview

import android.content.Context
import android.text.format.DateUtils
import com.fsck.k9.mail.Message
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.hasDmarcFail
import com.fsck.k9.mailstore.senderDomainOf
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.thunderbird.feature.impersonation.ReplyToMismatch
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.newsletter.StillMailing
import net.thunderbird.feature.newsletter.UnsubscribeHistory

/**
 * Headers only a mailing list or bulk sender puts on a message.
 */
private val LIST_HEADERS = listOf("List-Id", "List-Post", "List-Unsubscribe")

/**
 * The `Precedence` values bulk and list mail carries.
 */
private val BULK_PRECEDENCE = setOf("bulk", "list", "junk")

/**
 * The message view's notes about a sender's history and where replies go: replies leaving for another domain, the
 * first mail from an address, and mail that kept coming after an unsubscribe.
 */
internal object SenderNotes {

    /**
     * Whether the message came from a mailing list or a bulk sender. A list sets its own address as Reply-To, and
     * newsletters come from new addresses all the time, so neither note means anything for these.
     */
    @JvmStatic
    fun isMailingList(message: Message): Boolean {
        return (message is LocalMessage && message.classification == MessageClass.NEWSLETTER) ||
            LIST_HEADERS.any { message.getHeader(it).isNotEmpty() } ||
            message.getHeader("Precedence").any { it.trim().lowercase() in BULK_PRECEDENCE }
    }

    @JvmStatic
    fun replyToAddresses(message: Message): List<String> = message.replyTo.mapNotNull { it.address }

    /**
     * Whether a message that shows one of the reader's own addresses as its sender failed the sender check of the
     * account's own server - mail forged to look like it came from the reader, as "your account was hacked" scams are.
     * Without a verdict from that server nothing is said: it is the only one whose word is taken.
     *
     * @param isOwnAddress whether the sender address is one of the reader's identities, in any account.
     */
    @JvmStatic
    fun claimsToBeFromReader(
        isOwnAddress: Boolean,
        senderAddress: String?,
        authenticationResults: List<String>,
        trustedServerId: String?,
    ): Boolean {
        return isOwnAddress && hasDmarcFail(authenticationResults, senderDomainOf(senderAddress), trustedServerId)
    }

    @JvmStatic
    fun replyToText(context: Context, mismatch: ReplyToMismatch): String {
        return context.getString(
            R.string.message_view_reply_to_elsewhere,
            mismatch.replyToAddress,
            mismatch.senderDomain,
        )
    }

    /**
     * When the message reached the mailbox, which is what the sender's promise to stop is measured against. Falls
     * back to the date the sender wrote on it.
     */
    @JvmStatic
    fun receivedAt(message: Message): Long? {
        return message.internalDate?.time?.takeIf { it > 0 } ?: message.sentDate?.time
    }

    @JvmStatic
    fun stillMailingText(context: Context, stillMailing: StillMailing, now: Long): String {
        val date = DateUtils.formatDateTime(context, stillMailing.unsubscribedAt, DateUtils.FORMAT_SHOW_DATE)
        val days = ((now - stillMailing.unsubscribedAt) / DateUtils.DAY_IN_MILLIS).toInt()
        return context.resources.getQuantityString(R.plurals.message_view_still_mailing, days, date, days)
    }

    /**
     * Explains the note, and offers to move the message to spam, to see everything from the sender, or to forget the
     * unsubscribe for a reader who signed up again.
     *
     * @param onMarkSpam moves the message to spam; `null` when it cannot go there, as when it already is.
     * @param onShowAllMail opens everything the sender has sent.
     * @param onForgotten called after the reader forgot the unsubscribe.
     */
    @JvmStatic
    fun showStillMailingDialog(
        context: Context,
        stillMailing: StillMailing,
        unsubscribeHistory: UnsubscribeHistory,
        onMarkSpam: Runnable?,
        onShowAllMail: Runnable,
        onForgotten: Runnable,
    ) {
        val date = DateUtils.formatDateTime(context, stillMailing.unsubscribedAt, DateUtils.FORMAT_SHOW_DATE)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.message_view_still_mailing_dialog_title)
            .setMessage(
                context.getString(R.string.message_view_still_mailing_dialog_message, stillMailing.senderAddress, date),
            )
            .setNeutralButton(R.string.message_view_still_mailing_forget) { _, _ ->
                unsubscribeHistory.forget(stillMailing.senderAddress)
                onForgotten.run()
            }
            .setNegativeButton(R.string.message_view_still_mailing_all_mail) { _, _ -> onShowAllMail.run() }

        if (onMarkSpam != null) {
            builder.setPositiveButton(R.string.message_view_still_mailing_mark_spam) { _, _ -> onMarkSpam.run() }
        }

        builder.show()
    }
}
