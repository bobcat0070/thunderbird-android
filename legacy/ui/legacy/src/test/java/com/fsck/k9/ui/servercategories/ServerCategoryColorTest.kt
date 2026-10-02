package com.fsck.k9.ui.servercategories

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.Test

class ServerCategoryColorTest {

    @Test
    fun `a preset Outlook category should get the colour it is named after`() {
        assertThat(ServerCategoryColor.hueOf("Red category")).isEqualTo(4f)
        assertThat(ServerCategoryColor.hueOf("Blue category")).isEqualTo(212f)
        assertThat(ServerCategoryColor.hueOf("GREEN CATEGORY")).isEqualTo(130f)
    }

    @Test
    fun `a colour name inside another word should not count`() {
        // "Redesign" is not red. Its colour comes from the whole name, like any other category's.
        assertThat(ServerCategoryColor.hueOf("Redesign")).isEqualTo(ServerCategoryColor.hueOf("redesign"))
        assertThat(ServerCategoryColor.hueOf("Redesign")).isNotEqualTo(ServerCategoryColor.hueOf("Red"))
    }

    @Test
    fun `the same name should always get the same colour`() {
        assertThat(ServerCategoryColor.hueOf("Project X")).isEqualTo(ServerCategoryColor.hueOf("Project X"))
        assertThat(ServerCategoryColor.hueOf(" project x ")).isEqualTo(ServerCategoryColor.hueOf("Project X"))
    }

    @Test
    fun `a hue should be an angle on the colour wheel`() {
        for (name in listOf("Project X", "Kunden", "", "家族", "a", "Travel 2026")) {
            assertThat(ServerCategoryColor.hueOf(name)).isBetween(0f, 359f)
        }
    }
}
