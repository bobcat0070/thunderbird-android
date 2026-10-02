package com.fsck.k9.ui.servercategories

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.ui.R
import com.google.android.material.color.MaterialColors

/**
 * Builds the line under a message's subject that says how important the sender marked it and which categories
 * the server keeps on it.
 */
object MessageLabels {
    private const val SEPARATOR = "  "

    /**
     * @return the text of the line, or `null` when the message is of normal importance and has no categories,
     *   in which case there is nothing to show.
     */
    @JvmStatic
    fun build(context: Context, importance: MessageImportance, serverCategories: List<String>): CharSequence? {
        if (importance == MessageImportance.NORMAL && serverCategories.isEmpty()) return null

        val builder = SpannableStringBuilder()
        appendImportance(context, builder, importance)

        if (serverCategories.isNotEmpty()) {
            if (builder.isNotEmpty()) builder.append(SEPARATOR)
            ServerCategoryChipSpan.appendTo(builder, serverCategories, context.resources.displayMetrics.density)
        }

        return builder
    }

    private fun appendImportance(context: Context, builder: SpannableStringBuilder, importance: MessageImportance) {
        val (textResId, colorAttribute) = when (importance) {
            MessageImportance.HIGH -> R.string.message_importance_high to androidx.appcompat.R.attr.colorError
            MessageImportance.LOW ->
                R.string.message_importance_low to com.google.android.material.R.attr.colorOnSurfaceVariant

            MessageImportance.NORMAL -> return
        }

        val start = builder.length
        builder.append(context.getString(textResId))
        builder.setSpan(
            ForegroundColorSpan(MaterialColors.getColor(context, colorAttribute, 0)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (importance == MessageImportance.HIGH) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
