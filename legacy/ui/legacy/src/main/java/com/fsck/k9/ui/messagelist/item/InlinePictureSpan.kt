package com.fsck.k9.ui.messagelist.item

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/**
 * A small picture drawn inline in a line of text, centred on the text.
 *
 * Its space is reserved from the start and the picture filled in when it arrives, so a line of names does not shift
 * sideways as pictures load one by one. Until then nothing is drawn in the space.
 *
 * Written rather than using ImageSpan, whose centred alignment needs a newer Android than this app supports.
 *
 * @param sizeInPx the width and height the picture is drawn at.
 * @param gapInPx the space left between the picture and the text after it.
 */
internal class InlinePictureSpan(
    private val sizeInPx: Int,
    private val gapInPx: Int,
) : ReplacementSpan() {

    var drawable: Drawable? = null

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int {
        // Taller than the text would otherwise make the line grow downwards only; spread the extra above and below
        // so the text keeps its place relative to the line.
        if (fontMetrics != null) {
            val textHeight = fontMetrics.descent - fontMetrics.ascent
            if (sizeInPx > textHeight) {
                val extra = sizeInPx - textHeight
                fontMetrics.ascent -= extra / 2
                fontMetrics.descent += extra - extra / 2
                fontMetrics.top = minOf(fontMetrics.top, fontMetrics.ascent)
                fontMetrics.bottom = maxOf(fontMetrics.bottom, fontMetrics.descent)
            }
        }

        return sizeInPx + gapInPx
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val picture = drawable ?: return
        val metrics = paint.fontMetricsInt
        val textCentre = y + (metrics.ascent + metrics.descent) / 2

        canvas.save()
        canvas.translate(x, (textCentre - sizeInPx / 2).toFloat())
        picture.setBounds(0, 0, sizeInPx, sizeInPx)
        picture.draw(canvas)
        canvas.restore()
    }
}
