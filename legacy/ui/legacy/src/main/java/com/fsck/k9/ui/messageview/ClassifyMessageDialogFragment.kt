package com.fsck.k9.ui.messageview

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.R as BaseR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.senderDomainOrNull

private const val ARG_SENDER_ADDRESS = "senderAddress"

/**
 * Asks what a message should have been classified as.
 *
 * A dialog rather than a submenu because the correction has two parts — what the mail is, and whether the
 * user means this sender or everyone at its domain — and a menu can only ask one thing at a time.
 *
 * Nothing is pre-selected. The user is here because the current answer is wrong, so offering it as the
 * default would only make it easier to confirm by accident.
 */
class ClassifyMessageDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val senderAddress = requireArguments().getString(ARG_SENDER_ADDRESS).orEmpty()

        val view = layoutInflater.inflate(R.layout.dialog_classify_message, null)
        val options = view.findViewById<RadioGroup>(R.id.classify_options)
        val wholeDomain = view.findViewById<CheckBox>(R.id.classify_whole_domain)

        val domain = senderAddress.senderDomainOrNull()
        if (domain == null) {
            wholeDomain.visibility = View.GONE
        } else {
            wholeDomain.text = getString(R.string.classify_apply_to_domain, domain)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.classify_message_title)
            .setMessage(getString(R.string.classify_message_description, senderAddress))
            .setView(view)
            .setNegativeButton(BaseR.string.cancel_action, null)
            .setPositiveButton(BaseR.string.okay_action) { _, _ ->
                val messageClass = options.checkedRadioButtonId.toMessageClass() ?: return@setPositiveButton

                setFragmentResult(
                    FRAGMENT_RESULT_KEY,
                    Bundle().apply {
                        putString(RESULT_MESSAGE_CLASS, messageClass.name)
                        putString(
                            RESULT_SCOPE,
                            if (wholeDomain.isChecked) RuleScope.DOMAIN.name else RuleScope.SENDER.name,
                        )
                    },
                )
            }
            .create()
    }

    private fun Int.toMessageClass(): MessageClass? = when (this) {
        R.id.classify_human -> MessageClass.HUMAN
        R.id.classify_notification -> MessageClass.NOTIFICATION
        R.id.classify_newsletter -> MessageClass.NEWSLETTER
        else -> null
    }

    companion object {
        const val FRAGMENT_RESULT_KEY = "classifyMessage"
        const val RESULT_MESSAGE_CLASS = "messageClass"
        const val RESULT_SCOPE = "scope"

        fun create(senderAddress: String): ClassifyMessageDialogFragment {
            return ClassifyMessageDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SENDER_ADDRESS, senderAddress)
                }
            }
        }
    }
}
