package com.fsck.k9.helper

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.PersistableBundle

/**
 * [ClipDescription.EXTRA_IS_SENSITIVE], which only exists from Android 13; older versions ignore the extra.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * Access the system clipboard
 */
class ClipboardManager(private val context: Context) {

    /**
     * Copy a text string to the system clipboard
     *
     * @param label User-visible label for the content.
     * @param text The actual text to be copied to the clipboard.
     */
    fun setText(label: String, text: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
    }

    /**
     * Copy a secret, like a sign-in code, to the system clipboard. Marked sensitive, so Android does not show it in
     * its clipboard preview or offer it in keyboards' clipboard suggestions.
     */
    fun setSensitiveText(label: String, text: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboardManager.setPrimaryClip(clip)
    }
}
