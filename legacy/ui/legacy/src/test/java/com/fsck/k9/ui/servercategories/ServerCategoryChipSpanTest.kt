package com.fsck.k9.ui.servercategories

import android.graphics.Color
import android.graphics.Paint
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

class ServerCategoryChipSpanTest : RobolectricTest() {

    @Test
    fun `a label should be as tall as the text it stands in for`() {
        // A line takes its height from what is on it. A label that does not report a height leaves a line made
        // of nothing but labels with none, which is how the categories of a message went missing from its header.
        val paint = PaintWithMetrics(top = -50, ascent = -40, descent = 10, bottom = 14, leading = 2)
        val fontMetrics = Paint.FontMetricsInt()
        val testSubject = ServerCategoryChipSpan(backgroundColor = Color.RED, density = 2f)

        testSubject.getSize(paint, "Red category", 0, 12, fontMetrics)

        assertThat(fontMetrics.top).isEqualTo(-50)
        assertThat(fontMetrics.ascent).isEqualTo(-40)
        assertThat(fontMetrics.descent).isEqualTo(10)
        assertThat(fontMetrics.bottom).isEqualTo(14)
        assertThat(fontMetrics.leading).isEqualTo(2)
    }

    @Test
    fun `measuring a label without asking for its height should not fail`() {
        val testSubject = ServerCategoryChipSpan(backgroundColor = Color.RED, density = 2f)

        testSubject.getSize(Paint(), "Red category", 0, 12, null)
    }

    private class PaintWithMetrics(
        private val top: Int,
        private val ascent: Int,
        private val descent: Int,
        private val bottom: Int,
        private val leading: Int,
    ) : Paint() {
        override fun getFontMetricsInt(): FontMetricsInt {
            return FontMetricsInt().also {
                it.top = top
                it.ascent = ascent
                it.descent = descent
                it.bottom = bottom
                it.leading = leading
            }
        }
    }
}
