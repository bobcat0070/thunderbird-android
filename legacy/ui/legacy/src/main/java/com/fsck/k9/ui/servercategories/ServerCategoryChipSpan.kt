package com.fsck.k9.ui.servercategories

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * Draws the text it covers as a small rounded label, the way a category is shown next to a message.
 *
 * A span rather than a view, so that labels can sit inside the text of a list row and wrap with it.
 */
class ServerCategoryChipSpan(
    @param:ColorInt private val backgroundColor: Int,
    private val density: Float,
) : ReplacementSpan() {
    private val bounds = RectF()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int {
        // A line takes its height from the text on it, and a replacement span counts as none unless it says how
        // tall it is. Without this, a line holding nothing but labels has no height at all.
        if (fontMetrics != null) {
            val textMetrics = paint.fontMetricsInt
            fontMetrics.top = textMetrics.top
            fontMetrics.ascent = textMetrics.ascent
            fontMetrics.descent = textMetrics.descent
            fontMetrics.bottom = textMetrics.bottom
            fontMetrics.leading = textMetrics.leading
        }

        return (labelWidth(paint, text, start, end) + END_MARGIN_DP * density).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val originalColor = paint.color
        val originalTextSize = paint.textSize
        val originalFakeBold = paint.isFakeBoldText

        val textTop = y + paint.fontMetrics.ascent
        val textBottom = y + paint.fontMetrics.descent
        bounds.set(x, textTop, x + labelWidth(paint, text, start, end), textBottom)

        paint.color = backgroundColor
        canvas.drawRoundRect(bounds, CORNER_RADIUS_DP * density, CORNER_RADIUS_DP * density, paint)

        paint.color = Color.WHITE
        paint.textSize = originalTextSize * TEXT_SCALE
        paint.isFakeBoldText = false
        val textY = bounds.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText(text, start, end, x + HORIZONTAL_PADDING_DP * density, textY, paint)

        paint.color = originalColor
        paint.textSize = originalTextSize
        paint.isFakeBoldText = originalFakeBold
    }

    private fun labelWidth(paint: Paint, text: CharSequence, start: Int, end: Int): Float {
        val originalTextSize = paint.textSize
        paint.textSize = originalTextSize * TEXT_SCALE
        val textWidth = paint.measureText(text, start, end)
        paint.textSize = originalTextSize

        return textWidth + 2 * HORIZONTAL_PADDING_DP * density
    }

    companion object {
        private const val TEXT_SCALE = 0.85f
        private const val HORIZONTAL_PADDING_DP = 5f
        private const val CORNER_RADIUS_DP = 4f
        private const val END_MARGIN_DP = 4f

        /**
         * Appends a label for each of [categories] to [builder].
         *
         * @param maxLabels how many categories get a label of their own; any others are counted in a last label.
         */
        fun appendTo(
            builder: SpannableStringBuilder,
            categories: List<String>,
            density: Float,
            maxLabels: Int = Int.MAX_VALUE,
        ) {
            for (label in labelsFor(categories, maxLabels)) {
                val start = builder.length
                builder.append(label.text)
                builder.setSpan(
                    ServerCategoryChipSpan(label.backgroundColor, density),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        internal fun labelsFor(categories: List<String>, maxLabels: Int): List<ServerCategoryLabel> {
            val shown = categories.take(maxLabels).map { category ->
                ServerCategoryLabel(category, ServerCategoryColor.backgroundColorOf(category))
            }
            val hiddenCount = categories.size - shown.size

            return if (hiddenCount > 0) shown + ServerCategoryLabel("+$hiddenCount", Color.GRAY) else shown
        }
    }
}

internal data class ServerCategoryLabel(
    val text: String,
    @get:ColorInt val backgroundColor: Int,
)
