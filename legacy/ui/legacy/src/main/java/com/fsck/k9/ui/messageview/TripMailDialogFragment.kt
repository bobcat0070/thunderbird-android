package com.fsck.k9.ui.messageview

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.R as BaseR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.thunderbird.feature.trips.TravelSenders
import org.koin.android.ext.android.inject

private const val ARG_MESSAGE_ID = "messageId"
private const val ARG_SENDER_ADDRESS = "senderAddress"

/**
 * Marks a mail as trip mail for the Trips screen, the mail alone or everything from its sender's domain, and takes
 * either mark back. Each choice starts as it stands, so the dialog also shows what Trips already reads.
 */
class TripMailDialogFragment : DialogFragment() {
    private val travelSenders: TravelSenders by inject()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val messageId = arguments.getString(ARG_MESSAGE_ID)?.takeIf { it.isNotBlank() }
        val domain = arguments.getString(ARG_SENDER_ADDRESS)?.let(travelSenders::domainOf)

        val choices = listOfNotNull(
            messageId?.let { Choice(getString(R.string.trip_mail_this_mail), travelSenders.isMarked(it)) },
            domain?.let { Choice(getString(R.string.trip_mail_all_from_domain, it), travelSenders.isListed(it)) },
        )
        val checked = choices.map { it.isChecked }.toBooleanArray()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trip_mail_title)
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(BaseR.string.cancel_action, null)
            .setPositiveButton(BaseR.string.okay_action) { _, _ ->
                var index = 0
                messageId?.let { travelSenders.setMarked(it, checked[index++]) }
                domain?.let { travelSenders.setListed(it, checked[index]) }
                Toast.makeText(requireContext(), R.string.trip_mail_saved, Toast.LENGTH_SHORT).show()
            }
            .create()
    }

    private class Choice(val label: String, val isChecked: Boolean)

    companion object {
        fun create(messageId: String?, senderAddress: String?): TripMailDialogFragment {
            return TripMailDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE_ID, messageId)
                    putString(ARG_SENDER_ADDRESS, senderAddress)
                }
            }
        }
    }
}
