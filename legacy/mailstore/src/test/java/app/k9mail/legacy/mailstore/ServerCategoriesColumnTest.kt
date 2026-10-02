package app.k9mail.legacy.mailstore

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class ServerCategoriesColumnTest {

    @Test
    fun `categories should survive being written to the column and read back`() {
        val categories = listOf("Red category", "Project X, phase 2", "Größe")

        val result = ServerCategoriesColumn.decode(ServerCategoriesColumn.encode(categories))

        assertThat(result).isEqualTo(categories)
    }

    @Test
    fun `a message without categories should leave the column empty`() {
        assertThat(ServerCategoriesColumn.encode(emptyList())).isNull()
        assertThat(ServerCategoriesColumn.encode(listOf(" ", ""))).isNull()
    }

    @Test
    fun `an empty column should read as no categories`() {
        assertThat(ServerCategoriesColumn.decode(null)).isEmpty()
        assertThat(ServerCategoriesColumn.decode("")).isEmpty()
    }

    @Test
    fun `a name should not be able to pass for two`() {
        // Names are stored one per line, so a line break inside one would split it when read back.
        val result = ServerCategoriesColumn.decode(ServerCategoriesColumn.encode(listOf("Two\r\nlines", "Other")))

        assertThat(result).isEqualTo(listOf("Two lines", "Other"))
    }

    @Test
    fun `names should be trimmed and listed once`() {
        val result = ServerCategoriesColumn.normalize(listOf(" Red ", "Red", "", "Blue"))

        assertThat(result).isEqualTo(listOf("Red", "Blue"))
    }
}
