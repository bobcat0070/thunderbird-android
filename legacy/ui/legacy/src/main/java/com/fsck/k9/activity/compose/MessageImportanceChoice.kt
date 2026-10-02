package com.fsck.k9.activity.compose

import android.content.Context
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.ui.R

/**
 * The importance choices offered when writing a message, in the order they are listed.
 */
object MessageImportanceChoice {
    private val CHOICES = listOf(MessageImportance.HIGH, MessageImportance.NORMAL, MessageImportance.LOW)

    @JvmStatic
    fun labels(context: Context): Array<CharSequence> {
        return CHOICES.map { importance ->
            context.getString(
                when (importance) {
                    MessageImportance.HIGH -> R.string.compose_importance_high
                    MessageImportance.NORMAL -> R.string.compose_importance_normal
                    MessageImportance.LOW -> R.string.compose_importance_low
                },
            )
        }.toTypedArray()
    }

    @JvmStatic
    fun indexOf(importance: MessageImportance): Int = CHOICES.indexOf(importance)

    @JvmStatic
    fun at(index: Int): MessageImportance = CHOICES.getOrElse(index) { MessageImportance.NORMAL }

    /**
     * @param name the name an importance was saved under, or `null` when none was.
     */
    @JvmStatic
    fun fromSavedState(name: String?): MessageImportance {
        return MessageImportance.entries.firstOrNull { it.name == name } ?: MessageImportance.NORMAL
    }
}
