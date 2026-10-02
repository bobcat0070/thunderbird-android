package com.fsck.k9.ui.servercategories

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Picks the colour a server category is shown in.
 *
 * The colours Outlook shows are part of the mailbox settings, which the app is not permitted to read. A category
 * therefore gets a colour worked out from its name: the same name always gets the same colour, and Outlook's
 * preset categories, which are named after their colour, get that colour.
 */
object ServerCategoryColor {
    private const val SATURATION = 0.55f
    private const val LIGHTNESS = 0.42f
    private const val FULL_CIRCLE = 360

    /**
     * The hues of the colours Outlook names its preset categories after, for mailboxes in English.
     */
    private val NAMED_HUES = mapOf(
        "red" to 4f,
        "orange" to 28f,
        "yellow" to 46f,
        "green" to 130f,
        "blue" to 212f,
        "purple" to 276f,
    )

    private val WORD_SEPARATORS = Regex("[^\\p{L}]+")

    /**
     * The hue for [categoryName], in degrees.
     */
    fun hueOf(categoryName: String): Float {
        val words = categoryName.lowercase().split(WORD_SEPARATORS)
        NAMED_HUES.entries.firstOrNull { (colorName, _) -> colorName in words }?.let { return it.value }

        return categoryName.trim().lowercase().hashCode().mod(FULL_CIRCLE).toFloat()
    }

    /**
     * A background for [categoryName] that white text is readable on, in a light and in a dark theme alike.
     */
    @ColorInt
    fun backgroundColorOf(categoryName: String): Int {
        return ColorUtils.HSLToColor(floatArrayOf(hueOf(categoryName), SATURATION, LIGHTNESS))
    }
}
