package com.fsck.k9.ui.messageview

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

class RemoteImageSenderStoreTest : RobolectricTest() {
    private val testSubject = RemoteImageSenderStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `an unknown sender should not be trusted`() {
        // The absence of an entry is the safe answer, so a lost store asks again rather than loading silently.
        assertThat(testSubject.isTrusted("stranger@example.com")).isFalse()
    }

    @Test
    fun `a trusted sender should be trusted`() {
        testSubject.trust("sam@example.com", RemoteImageScope.SENDER)

        assertThat(testSubject.isTrusted("sam@example.com")).isTrue()
    }

    @Test
    fun `trust should ignore case`() {
        testSubject.trust("Sam@Example.COM", RemoteImageScope.SENDER)

        assertThat(testSubject.isTrusted("sam@example.com")).isTrue()
    }

    @Test
    fun `trusting one sender should not trust their domain`() {
        testSubject.trust("sam@example.com", RemoteImageScope.SENDER)

        assertThat(testSubject.isTrusted("someone-else@example.com")).isFalse()
    }

    @Test
    fun `a trusted domain should cover any address at it`() {
        // Why the domain option exists: shops rotate the local part, so a sender rule never matches twice.
        testSubject.trust("news-8f21@shop.com", RemoteImageScope.DOMAIN)

        assertThat(testSubject.isTrusted("news-9c04@shop.com")).isTrue()
    }

    @Test
    fun `a trusted domain should not cover a lookalike domain`() {
        testSubject.trust("news@shop.com", RemoteImageScope.DOMAIN)

        assertThat(testSubject.isTrusted("news@notshop.com")).isFalse()
        assertThat(testSubject.isTrusted("news@shop.com.evil.test")).isFalse()
    }

    @Test
    fun `forgetting a sender should stop trusting them`() {
        testSubject.trust("sam@example.com", RemoteImageScope.SENDER)

        testSubject.forget("sam@example.com", RemoteImageScope.SENDER)

        assertThat(testSubject.isTrusted("sam@example.com")).isFalse()
    }

    @Test
    fun `forgetting a sender should leave a trusted domain alone`() {
        testSubject.trust("sam@example.com", RemoteImageScope.SENDER)
        testSubject.trust("sam@example.com", RemoteImageScope.DOMAIN)

        testSubject.forget("sam@example.com", RemoteImageScope.SENDER)

        assertThat(testSubject.isTrusted("sam@example.com")).isTrue()
    }

    @Test
    fun `an address with no domain should never be trusted`() {
        testSubject.trust("not-an-address", RemoteImageScope.DOMAIN)

        assertThat(testSubject.isTrusted("not-an-address")).isFalse()
    }

    @Test
    fun `a blank address should not be trusted`() {
        assertThat(testSubject.isTrusted("   ")).isFalse()
    }
}
