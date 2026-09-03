package com.fsck.k9.ui.messagelist.item

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.k9mail.core.ui.legacy.designsystem.R as DesignSystemR
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messagelist.MessageListViewItem
import com.google.android.material.textview.MaterialTextView
import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * A collapsed group of bulk mail.
 *
 * The row names the senders inside rather than only counting them: a count says how much was hidden, but the
 * senders are what let someone decide at a glance whether opening it is worth their time.
 */
class BundleViewHolder(
    view: View,
    private val onBundleClicked: (MessageClass) -> Unit,
) : MessageListViewHolder(view) {

    private val icon: ImageView = view.findViewById(R.id.bundle_icon)
    private val title: MaterialTextView = view.findViewById(R.id.bundle_title)
    private val senders: MaterialTextView = view.findViewById(R.id.bundle_senders)
    private val chevron: ImageView = view.findViewById(R.id.bundle_chevron)

    private var boundClass: MessageClass? = null

    init {
        view.setOnClickListener {
            boundClass?.let(onBundleClicked)
        }
    }

    fun bind(bundle: MessageListViewItem.Bundle) {
        boundClass = bundle.messageClass

        val resources = itemView.resources
        val label = resources.getString(bundle.messageClass.titleRes())

        // The unread count is what the user is deciding about; the total is what they get if they open it.
        title.text = if (bundle.unreadCount > 0) {
            resources.getString(R.string.message_list_bundle_title_unread, label, bundle.unreadCount)
        } else {
            resources.getString(R.string.message_list_bundle_title, label, bundle.messageCount)
        }

        senders.text = bundle.senderNames.joinToString(separator = ", ")
        senders.visibility = if (bundle.senderNames.isEmpty()) View.GONE else View.VISIBLE

        icon.setImageResource(bundle.messageClass.iconRes())
        chevron.setImageResource(
            if (bundle.isExpanded) DesignSystemR.drawable.ic_expand_less else DesignSystemR.drawable.ic_expand_more,
        )
    }

    private fun MessageClass.titleRes(): Int = when (this) {
        MessageClass.NOTIFICATION -> R.string.message_list_bundle_notifications
        else -> R.string.message_list_bundle_newsletters
    }

    private fun MessageClass.iconRes(): Int = when (this) {
        MessageClass.NOTIFICATION -> DesignSystemR.drawable.ic_notifications
        else -> DesignSystemR.drawable.ic_mail
    }

    companion object {
        fun create(
            layoutInflater: LayoutInflater,
            parent: ViewGroup,
            onBundleClicked: (MessageClass) -> Unit,
        ): BundleViewHolder {
            val view = layoutInflater.inflate(R.layout.message_list_item_bundle, parent, false)

            return BundleViewHolder(view, onBundleClicked)
        }
    }
}
