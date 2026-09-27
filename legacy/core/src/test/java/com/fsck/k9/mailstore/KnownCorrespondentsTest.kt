package com.fsck.k9.mailstore

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.SentMailRecipientScanner
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class KnownCorrespondentsTest : RobolectricTest() {
    private val recipientIndex = RecipientIndex(ApplicationProvider.getApplicationContext())
    private val scanner = mock<SentMailRecipientScanner>()
    private val testSubject = KnownCorrespondents(recipientIndex, scanner)

    @Test
    fun `an address never written to should not be known`() {
        sent("sam@example.com", times = 2)

        assertThat(testSubject.isKnown("stranger@example.com")).isFalse()
    }

    @Test
    fun `an address written to twice should be known`() {
        sent("sam@example.com", times = 2)

        assertThat(testSubject.isKnown("sam@example.com")).isTrue()
    }

    @Test
    fun `an address written to once should not be known`() {
        // Replying once to a marketing address is ordinary, and one reply would otherwise promote everything
        // that sender ever sends above its own bulk headers.
        sent("news@shop.example", times = 1)

        assertThat(testSubject.isKnown("news@shop.example")).isFalse()
    }

    @Test
    fun `matching should ignore case and surrounding space`() {
        sent("Sam@Example.COM", times = 2)

        assertThat(testSubject.isKnown("  SAM@example.com  ")).isTrue()
    }

    @Test
    fun `a blank address should not be known`() {
        assertThat(testSubject.isKnown("   ")).isFalse()
    }

    @Test
    fun `mail sent from elsewhere should be picked up before answering`() {
        testSubject.isKnown("sam@example.com")

        verify(scanner).scanIfDue()
    }

    private fun sent(address: String, times: Int) {
        repeat(times) { recipientIndex.recordSent(address, displayName = null, at = 1000L + it) }
    }
}
