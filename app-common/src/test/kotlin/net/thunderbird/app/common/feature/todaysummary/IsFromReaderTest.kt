package net.thunderbird.app.common.feature.todaysummary

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class IsFromReaderTest {
    private val ownAddresses = setOf("sam@example.org", "sam.reader@work.example")

    @Test
    fun `mail from one of the reader's addresses should be from the reader`() {
        assertThat(isFromReader("sam@example.org", ownAddresses)).isTrue()
    }

    @Test
    fun `mail from another of the reader's accounts should be from the reader whatever its case`() {
        assertThat(isFromReader("Sam.Reader@Work.example", ownAddresses)).isTrue()
    }

    @Test
    fun `mail from someone else should not be from the reader`() {
        assertThat(isFromReader("alex@example.org", ownAddresses)).isFalse()
    }

    @Test
    fun `mail without a sender address should not be from the reader`() {
        assertThat(isFromReader(null, ownAddresses)).isFalse()
    }
}
