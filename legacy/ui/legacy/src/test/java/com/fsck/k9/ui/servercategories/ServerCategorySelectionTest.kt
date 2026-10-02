package com.fsck.k9.ui.servercategories

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Test

class ServerCategorySelectionTest {

    @Test
    fun `the categories in use should be offered in alphabetical order with the assigned ones ticked`() {
        val testSubject = ServerCategorySelection.create(
            assigned = listOf("Red category"),
            known = listOf("Red category", "blue category", "Project X"),
        )

        assertThat(testSubject.options).containsExactly("blue category", "Project X", "Red category")
        assertThat(testSubject.selected).containsExactly("Red category")
    }

    @Test
    fun `a category only this message has should still be offered`() {
        val testSubject = ServerCategorySelection.create(assigned = listOf("Only here"), known = listOf("Other"))

        assertThat(testSubject.options).containsExactly("Only here", "Other")
        assertThat(testSubject.selected).containsExactly("Only here")
    }

    @Test
    fun `ticking and unticking should change what is selected`() {
        val testSubject = ServerCategorySelection.create(assigned = listOf("B"), known = listOf("A", "B", "C"))

        val result = testSubject.select("C", isSelected = true).select("B", isSelected = false)

        assertThat(result.selected).containsExactly("C")
    }

    @Test
    fun `what is selected should be listed in the order it is offered`() {
        val testSubject = ServerCategorySelection.create(assigned = emptyList(), known = listOf("A", "B", "C"))

        val result = testSubject.select("C", isSelected = true).select("A", isSelected = true)

        assertThat(result.selected).containsExactly("A", "C")
    }

    @Test
    fun `a typed category should be offered and ticked`() {
        val testSubject = ServerCategorySelection.create(assigned = emptyList(), known = listOf("A"))

        val result = testSubject.add("  New one ")

        assertThat(result.options).containsExactly("A", "New one")
        assertThat(result.selected).containsExactly("New one")
    }

    @Test
    fun `typing a category that is already offered should tick that one`() {
        // Outlook treats names that differ only in case as the same category.
        val testSubject = ServerCategorySelection.create(assigned = emptyList(), known = listOf("Red category"))

        val result = testSubject.add("red CATEGORY")

        assertThat(result.options).containsExactly("Red category")
        assertThat(result.selected).containsExactly("Red category")
    }

    @Test
    fun `typing nothing should change nothing`() {
        val testSubject = ServerCategorySelection.create(assigned = emptyList(), known = listOf("A"))

        assertThat(testSubject.add("   ")).isEqualTo(testSubject)
    }

    @Test
    fun `a selection should survive being saved and restored`() {
        val testSubject = ServerCategorySelection.create(assigned = listOf("B"), known = listOf("A", "B", "-C", "+D"))
            .select("+D", isSelected = true)

        val result = ServerCategorySelection.restore(testSubject.save())

        assertThat(result).isEqualTo(testSubject)
    }

    @Test
    fun `an empty selection should restore as empty`() {
        val result = ServerCategorySelection.restore(ServerCategorySelection().save())

        assertThat(result.options).isEmpty()
        assertThat(result.selected).isEmpty()
    }
}
