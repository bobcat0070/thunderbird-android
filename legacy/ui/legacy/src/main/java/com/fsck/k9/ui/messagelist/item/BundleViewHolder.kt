package com.fsck.k9.ui.messagelist.item

import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.k9mail.core.ui.legacy.designsystem.R as DesignSystemR
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.fsck.k9.contacts.ContactPictureLoader
import com.fsck.k9.mail.Address
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messagelist.BundleSender
import com.fsck.k9.ui.messagelist.MessageListViewItem
import com.google.android.material.textview.MaterialTextView
import kotlin.math.roundToInt
import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * A row standing in for every message of one class.
 *
 * It names the senders inside rather than only counting them: a count says how much was lifted out of the
 * list, but the senders are what let someone decide at a glance whether opening it is worth their time.
 */
class BundleViewHolder(
    view: View,
    private val onBundleClicked: (MessageClass) -> Unit,
    private val contactPictureLoader: ContactPictureLoader,
    private val showSenderPictures: () -> Boolean,
) : MessageListViewHolder(view) {

    private val icon: ImageView = view.findViewById(R.id.bundle_icon)
    private val title: MaterialTextView = view.findViewById(R.id.bundle_title)
    private val senders: MaterialTextView = view.findViewById(R.id.bundle_senders)
    private var boundClass: MessageClass? = null
    private val pictureLoads = mutableListOf<CustomTarget<Drawable>>()

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

        bindSenders(bundle.senders)

        icon.setImageResource(bundle.messageClass.iconRes())
    }

    /**
     * Names the senders inside, each with the same small picture the message list would show for them.
     *
     * The pictures come from the same sources as the list's avatars and behind the same checks - a brand logo only
     * for mail that passed DMARC, nothing fetched from the network unless the user turned that source on - so a
     * category row never shows a sender differently from how their own messages are shown. With sender pictures
     * switched off, it stays a line of names.
     */
    private fun bindSenders(bundleSenders: List<BundleSender>) {
        cancelPictureLoads()

        senders.visibility = if (bundleSenders.isEmpty()) View.GONE else View.VISIBLE
        // What a screen reader says, whatever is drawn: the placeholder a picture sits in means nothing read aloud.
        senders.contentDescription = bundleSenders.joinToString(separator = ", ") { it.name }

        senders.text = if (showSenderPictures()) {
            sendersWithPictures(bundleSenders)
        } else {
            bundleSenders.joinToString(separator = ", ") { it.name }
        }
    }

    private fun sendersWithPictures(bundleSenders: List<BundleSender>): CharSequence {
        // Sized from the text rather than fixed, so the pictures follow the user's font size.
        val pictureSize = (senders.textSize * PICTURE_TO_TEXT_SIZE).roundToInt()
        val gap = (senders.textSize * GAP_TO_TEXT_SIZE).roundToInt()

        return SpannableStringBuilder().apply {
            bundleSenders.forEachIndexed { index, sender ->
                if (index > 0) append(SENDER_SEPARATOR)

                val address = sender.address
                if (address != null) {
                    val span = InlinePictureSpan(pictureSize, gap)
                    val start = length
                    append(PICTURE_PLACEHOLDER)
                    setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    loadPicture(span, address, sender.isSenderAuthenticated, pictureSize)
                }

                append(sender.name)
            }
        }
    }

    private fun loadPicture(span: InlinePictureSpan, address: Address, isAuthenticated: Boolean, size: Int) {
        val target = object : CustomTarget<Drawable>(size, size) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                span.drawable = resource
                senders.invalidate()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                span.drawable = placeholder
                senders.invalidate()
            }
        }

        pictureLoads += target
        contactPictureLoader.loadContactPicture(address, isAuthenticated, size, target)
    }

    /**
     * A reused row must not have the previous bundle's pictures arrive into it.
     */
    private fun cancelPictureLoads() {
        pictureLoads.forEach(contactPictureLoader::clear)
        pictureLoads.clear()
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
        /**
         * The picture's height as a share of the text size: about a capital letter plus its descender, which is
         * what keeps a row of pictures and names looking like one line rather than two.
         */
        private const val PICTURE_TO_TEXT_SIZE = 1.15f
        private const val GAP_TO_TEXT_SIZE = 0.3f

        /**
         * The character a picture is drawn over. Object replacement is what the character exists for.
         */
        private const val PICTURE_PLACEHOLDER = "\uFFFC"

        /**
         * Space rather than a comma between senders: with a picture starting each name, the pictures already mark
         * where one sender ends and the next begins.
         */
        private const val SENDER_SEPARATOR = "   "

        fun create(
            layoutInflater: LayoutInflater,
            parent: ViewGroup,
            onBundleClicked: (MessageClass) -> Unit,
            contactPictureLoader: ContactPictureLoader,
            showSenderPictures: () -> Boolean,
        ): BundleViewHolder {
            val view = layoutInflater.inflate(R.layout.message_list_item_bundle, parent, false)

            return BundleViewHolder(view, onBundleClicked, contactPictureLoader, showSenderPictures)
        }
    }
}
