package com.fsck.k9.activity.compose

import android.app.Activity
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messageview.SenderNotes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.impersonation.ReplyToChecker
import net.thunderbird.feature.impersonation.ReplyToMismatch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Asks before a reply goes to a Reply-To address at another domain than the sender's, offering to send it to the
 * sender instead.
 *
 * Asked where the reply is written rather than where Reply is pressed, so a reply started from the message, the
 * list or a notification is checked alike.
 */
internal object ReplyToConfirmation : KoinComponent {
    private val replyToChecker: ReplyToChecker by inject()

    /**
     * The check may read who the reader writes to, so it runs off the main thread.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Called on the main thread when the reader chose to reply to the sender instead.
     */
    fun interface OnReplyToSender {
        /**
         * @param replyTo the Reply-To addresses to take out of the reply.
         * @param sender the sender's addresses to put in.
         */
        fun replyToSender(replyTo: List<String>, sender: Array<Address>)
    }

    /**
     * @param onReplyToSender called on the main thread when the reader chose to reply to the sender instead, with
     *   the Reply-To addresses to take out and the sender's addresses to put in.
     */
    @JvmStatic
    fun check(
        activity: Activity,
        message: Message,
        account: LegacyAccount,
        onReplyToSender: OnReplyToSender,
    ) {
        val sender = message.from.firstOrNull()?.takeIf { !account.isAnIdentity(it) }
        val replyTo = SenderNotes.replyToAddresses(message)
        if (sender == null || replyTo.isEmpty() || SenderNotes.isMailingList(message)) return

        executor.execute {
            val mismatch = replyToChecker.check(sender.address, replyTo)
            activity.runOnUiThread {
                if (mismatch != null && !activity.isFinishing && !activity.isDestroyed) {
                    showDialog(activity, mismatch) { onReplyToSender.replyToSender(replyTo, message.from) }
                }
            }
        }
    }

    private fun showDialog(activity: Activity, mismatch: ReplyToMismatch, onReplyToSender: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.compose_reply_to_elsewhere_title)
            .setMessage(
                activity.getString(
                    R.string.compose_reply_to_elsewhere_message,
                    mismatch.senderAddress,
                    mismatch.replyToAddress,
                ),
            )
            .setPositiveButton(R.string.compose_reply_to_elsewhere_sender) { _, _ -> onReplyToSender() }
            .setNegativeButton(R.string.compose_reply_to_elsewhere_keep, null)
            .show()
    }
}
