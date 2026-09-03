package com.fsck.k9.ui.messageview

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fsck.k9.ui.base.R as BaseR

private const val ARG_SENDER_ADDRESS = "senderAddress"

/**
 * Asks how widely to trust a sender's remote images.
 *
 * The choice is offered rather than assumed because the two answers give away different amounts. Trusting one
 * address is a decision about one correspondent; trusting a domain also covers senders the user has not seen
 * yet, which is what makes it useful for shops that rotate their sending address and what makes it worth
 * asking about rather than picking for them.
 */
class AlwaysShowImagesDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val senderAddress = requireArguments().getString(ARG_SENDER_ADDRESS).orEmpty()
        val domain = senderAddress.emailDomainOrNull()

        val view = layoutInflater.inflate(R.layout.dialog_always_show_images, null)
        val options = view.findViewById<RadioGroup>(R.id.always_show_images_options)
        val senderOption = view.findViewById<RadioButton>(R.id.always_show_images_sender)
        val domainOption = view.findViewById<RadioButton>(R.id.always_show_images_domain)

        senderOption.text = getString(R.string.message_view_always_show_remote_images_sender, senderAddress)
        if (domain == null) {
            domainOption.visibility = View.GONE
        } else {
            domainOption.text = getString(R.string.message_view_always_show_remote_images_domain, domain)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.message_view_always_show_remote_images_title)
            .setMessage(R.string.message_view_always_show_remote_images_description)
            .setView(view)
            .setNegativeButton(BaseR.string.cancel_action, null)
            .setPositiveButton(BaseR.string.okay_action) { _, _ ->
                val scope = if (options.checkedRadioButtonId == R.id.always_show_images_domain) {
                    RemoteImageScope.DOMAIN
                } else {
                    RemoteImageScope.SENDER
                }

                setFragmentResult(
                    FRAGMENT_RESULT_KEY,
                    Bundle().apply { putString(RESULT_SCOPE, scope.name) },
                )
            }
            .create()
    }

    companion object {
        const val FRAGMENT_RESULT_KEY = "alwaysShowImages"
        const val RESULT_SCOPE = "scope"

        fun create(senderAddress: String): AlwaysShowImagesDialogFragment {
            return AlwaysShowImagesDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_SENDER_ADDRESS, senderAddress) }
            }
        }
    }
}
