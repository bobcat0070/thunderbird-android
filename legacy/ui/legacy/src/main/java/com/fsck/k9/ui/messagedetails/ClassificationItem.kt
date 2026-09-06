package com.fsck.k9.ui.messagedetails

import android.view.View
import android.widget.ImageView
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import com.fsck.k9.ui.R
import com.google.android.material.textview.MaterialTextView
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem

/**
 * Says what kind of mail this was decided to be, and what decided it.
 *
 * The app already sorts a mailbox by this and has never said why. A reader who finds a message somewhere
 * surprising has no way to tell a rule they taught from a header the sender set, and no way to know whether
 * correcting it will help. The evidence is recorded with every message; this is where it is finally shown.
 */
internal class ClassificationItem(
    val categoryLabel: String,
    val reasonLabel: String,
) : AbstractItem<ClassificationItem.ViewHolder>() {
    override val type: Int = R.id.message_details_classification
    override val layoutRes = R.layout.message_details_classification_item

    override fun getViewHolder(v: View) = ViewHolder(v)

    class ViewHolder(view: View) : FastAdapter.ViewHolder<ClassificationItem>(view) {
        private val icon: ImageView = view.findViewById(R.id.classification_icon)
        private val category = view.findViewById<MaterialTextView>(R.id.classification_category)
        private val reason = view.findViewById<MaterialTextView>(R.id.classification_reason)

        override fun bindView(item: ClassificationItem, payloads: List<Any>) {
            icon.setImageResource(Icons.Outlined.FilterList)
            category.text = item.categoryLabel
            reason.text = item.reasonLabel
        }

        override fun unbindView(item: ClassificationItem) {
            category.text = null
            reason.text = null
        }
    }
}
