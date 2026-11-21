package com.fsck.k9.ui.messageview

import android.content.Context
import app.k9mail.feature.launcher.FeatureLauncherActivity
import app.k9mail.feature.launcher.FeatureLauncherTarget
import com.fsck.k9.mail.Message
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.plusaddress.PlusAddressDecisions
import net.thunderbird.feature.plusaddress.PlusAddressLeak

/**
 * The message view's note for mail on one of the reader's plus addresses from a sender it was not given to.
 */
internal object PlusAddressLeakNote {

    /**
     * Every address the message may have come in on: the one the delivering server recorded, which covers a blind
     * copy, then the To and Cc recipients.
     */
    @JvmStatic
    fun recipientsOf(message: Message, account: LegacyAccount): List<String> {
        val delivered = DeliveryAddressExtractor.extractDeliveryAddress(message, account)
        val written = sequenceOf(Message.RecipientType.TO, Message.RecipientType.CC)
            .flatMap { type -> message.getRecipients(type).orEmpty().asSequence() }
            .mapNotNull { it.address }

        return (listOfNotNull(delivered) + written).distinct()
    }

    @JvmStatic
    fun text(context: Context, leak: PlusAddressLeak): String {
        return context.getString(
            R.string.message_view_plus_address_leak,
            leak.plusAddress,
            leak.givenTo.sorted().joinToString(", "),
            leak.senderDomain,
        )
    }

    /**
     * Explains the note, and offers to accept the sender, to stop checking the address, or to see all plus
     * addresses.
     *
     * @param onDecided called after the reader accepted the sender or stopped checking the address.
     */
    @JvmStatic
    fun showDialog(context: Context, leak: PlusAddressLeak, decisions: PlusAddressDecisions, onDecided: Runnable) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.message_view_plus_address_leak_dialog_title)
            .setMessage(
                context.getString(
                    R.string.message_view_plus_address_leak_dialog_message,
                    leak.plusAddress,
                    leak.givenTo.sorted().joinToString(", "),
                    leak.senderDomain,
                ),
            )
            .setPositiveButton(R.string.message_view_plus_address_sender_is_fine) { _, _ ->
                decisions.allowSender(leak.plusAddress, leak.senderDomain)
                onDecided.run()
            }
            .setNegativeButton(R.string.message_view_plus_address_stop_checking) { _, _ ->
                decisions.setIgnored(leak.plusAddress, isIgnored = true)
                onDecided.run()
            }
            .setNeutralButton(R.string.message_view_plus_address_show_all) { _, _ ->
                FeatureLauncherActivity.launch(context, FeatureLauncherTarget.PlusAddresses)
            }
            .show()
    }
}
