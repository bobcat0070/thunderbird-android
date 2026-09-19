package com.fsck.k9.contacts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.fsck.k9.mail.Address

/**
 * Draw a `Bitmap` containing the "contact letter" obtained by [ContactLetterExtractor].
 *
 * Drawn as a disc on a transparent square rather than filling the square. The shape is what tells a reader
 * which kind of picture they are looking at - a round tile is initials the app made up, a square is a real
 * picture from somewhere - so it is baked into the bitmap, where it travels to every place the picture is
 * shown, notifications included, instead of being left to whichever view happens to display it.
 */
class ContactLetterBitmapCreator(
    private val letterExtractor: ContactLetterExtractor,
    val config: ContactLetterBitmapConfig,
) {
    fun drawBitmap(bitmap: Bitmap, pictureSizeInPx: Int, address: Address): Bitmap {
        val canvas = Canvas(bitmap)

        val backgroundColor = calcUnknownContactColor(address)
        bitmap.eraseColor(Color.TRANSPARENT)

        val radius = pictureSizeInPx / 2f
        canvas.drawCircle(
            radius,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor },
        )

        val letter = letterExtractor.extractContactLetter(address)

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            setARGB(255, 255, 255, 255)
            textSize = pictureSizeInPx.toFloat() * 0.65f
        }

        val rect = Rect()
        paint.getTextBounds(letter, 0, 1, rect)

        val width = paint.measureText(letter)
        canvas.drawText(
            letter,
            pictureSizeInPx / 2f - width / 2f,
            pictureSizeInPx / 2f + rect.height() / 2f,
            paint,
        )

        return bitmap
    }

    fun calcUnknownContactColor(address: Address): Int {
        if (config.hasDefaultBackgroundColor) {
            return config.defaultBackgroundColor
        }

        val hash = address.hashCode()
        val backgroundColors = config.backgroundColors
        val colorIndex = (hash and Integer.MAX_VALUE) % backgroundColors.size
        return backgroundColors[colorIndex]
    }

    fun signatureOf(address: Address): String {
        return calcUnknownContactColor(address).toString()
    }
}
