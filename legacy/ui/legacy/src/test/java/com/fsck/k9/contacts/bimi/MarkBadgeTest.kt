package com.fsck.k9.contacts.bimi

import android.graphics.Bitmap
import android.graphics.Color
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkBadgeTest : RobolectricTest() {

    @Test
    fun `a common mark should be left alone`() {
        // An authority vouched for it, just for something weaker than a trademark. There is nothing extra to
        // claim and nothing to warn about, so the logo is shown plainly.
        val bitmap = solidBitmap()

        val result = bitmap.withMarkBadge(MarkTrust.COMMON)

        assertThat(result.getPixel(BADGE_CENTRE, BADGE_CENTRE)).isEqualTo(Color.RED)
    }

    @Test
    fun `a verified mark should be badged`() {
        val bitmap = solidBitmap()

        val result = bitmap.withMarkBadge(MarkTrust.VERIFIED)

        assertThat(result.getPixel(BADGE_CENTRE, BADGE_CENTRE)).isNotEqualTo(Color.RED)
    }

    @Test
    fun `a self-asserted mark should be badged`() {
        // Shown, but never mistakable for the verified tier.
        val bitmap = solidBitmap()

        val result = bitmap.withMarkBadge(MarkTrust.SELF_ASSERTED)

        assertThat(result.getPixel(BADGE_CENTRE, BADGE_CENTRE)).isNotEqualTo(Color.RED)
    }

    @Test
    fun `the two badged tiers should not look the same`() {
        // The whole point of the badge is telling them apart.
        val verified = solidBitmap().withMarkBadge(MarkTrust.VERIFIED)
        val selfAsserted = solidBitmap().withMarkBadge(MarkTrust.SELF_ASSERTED)

        // Compared whole rather than by one pixel: both glyphs are white and both run through the middle of
        // the badge, so a single sample can match while the symbols plainly differ.
        assertThat(verified.sameAs(selfAsserted)).isFalse()
    }

    @Test
    fun `a badge should not cover the middle of the logo`() {
        val result = solidBitmap().withMarkBadge(MarkTrust.VERIFIED)

        assertThat(result.getPixel(SIZE / 2, SIZE / 2)).isEqualTo(Color.RED)
    }

    private fun solidBitmap(): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private companion object {
        const val SIZE = 120

        /**
         * The badge sits in the bottom-right corner at 38% of the avatar, so its centre is one radius in from
         * each edge. The extreme corner pixel lies outside the circle and says nothing.
         */
        const val BADGE_CENTRE = (SIZE - SIZE * 38 / 100 / 2)
    }
}
