package com.fsck.k9.contacts.bimi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * How much is known about a logo being shown, and therefore what is drawn on it.
 */
enum class MarkTrust {
    /**
     * An authority verified a registered trademark. The specification gives this tier a check, and it is the
     * only tier that gets one.
     */
    VERIFIED,

    /**
     * An authority verified something weaker than a registered trademark. Shown plainly: no mark on the logo,
     * because there is nothing extra to claim and nothing to warn about.
     */
    COMMON,

    /**
     * Nobody vouched for this logo but the domain publishing it. Shown, because a domain that passed DMARC
     * saying "this is our logo" is worth something, and marked with a question so it is never mistaken for
     * the verified tier.
     */
    SELF_ASSERTED,
}

private const val BADGE_DIAMETER_FRACTION = 0.38f
private const val BADGE_RING_FRACTION = 0.08f

private val VERIFIED_COLOR = Color.parseColor("#1A73E8")
private val SELF_ASSERTED_COLOR = Color.parseColor("#5F6368")

/**
 * Draws the trust badge onto a logo.
 *
 * Composited into the avatar rather than added to the row layout so it travels with the image everywhere an
 * avatar is drawn, and so it cannot be separated from the logo it qualifies.
 */
@Suppress("ReturnCount")
fun Bitmap.withMarkBadge(trust: MarkTrust): Bitmap {
    if (trust == MarkTrust.COMMON) return this

    // A bitmap decoded from a file is immutable, and a Canvas over one throws rather than failing softly -
    // which lost the whole avatar, not just the badge. Rendered marks arrive mutable and are drawn on
    // directly; anything else is copied first.
    val target = if (isMutable) this else copy(Bitmap.Config.ARGB_8888, true) ?: return this

    val size = target.width.coerceAtMost(target.height)
    val diameter = size * BADGE_DIAMETER_FRACTION
    val radius = diameter / 2f
    val centreX = target.width - radius
    val centreY = target.height - radius

    val canvas = Canvas(target)

    // A ring in the background colour keeps the badge readable against a logo of any colour.
    canvas.drawCircle(centreX, centreY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    canvas.drawCircle(
        centreX,
        centreY,
        radius - size * BADGE_RING_FRACTION / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (trust == MarkTrust.VERIFIED) VERIFIED_COLOR else SELF_ASSERTED_COLOR
        },
    )

    when (trust) {
        MarkTrust.VERIFIED -> canvas.drawCheck(centreX, centreY, radius)
        MarkTrust.SELF_ASSERTED -> canvas.drawQuestionMark(centreX, centreY, radius)
        MarkTrust.COMMON -> Unit
    }

    return target
}

private fun Canvas.drawCheck(centreX: Float, centreY: Float, radius: Float) {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = radius * 0.28f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val path = Path().apply {
        moveTo(centreX - radius * 0.42f, centreY)
        lineTo(centreX - radius * 0.10f, centreY + radius * 0.34f)
        lineTo(centreX + radius * 0.45f, centreY - radius * 0.34f)
    }

    drawPath(path, stroke)
}

/**
 * Drawn as text rather than a path: a question mark is a glyph, and hand-drawing one at avatar size looks
 * like a mistake rather than a symbol.
 */
private fun Canvas.drawQuestionMark(centreX: Float, centreY: Float, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = radius * 1.6f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    val baseline = centreY - (paint.descent() + paint.ascent()) / 2f
    drawText("?", centreX, baseline, paint)
}
