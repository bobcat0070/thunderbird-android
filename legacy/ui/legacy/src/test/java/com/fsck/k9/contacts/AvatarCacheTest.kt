package com.fsck.k9.contacts

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

class AvatarCacheTest : RobolectricTest() {

    @Test
    fun `an unknown key should hold nothing`() {
        assertThat(cache().get("nothing here")).isNull()
    }

    @Test
    fun `what was put should come back`() {
        val testSubject = cache()
        testSubject.put("sam@example.com", byteArrayOf(1, 2, 3))

        assertThat(testSubject.get("sam@example.com")?.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
    }

    @Test
    fun `a remembered miss should be distinguishable from an unknown key`() {
        // The whole point: most senders have no picture, so "we asked and there was nothing" has to survive
        // or the common case is the one that repeats.
        val testSubject = cache()
        testSubject.putMiss("stranger@example.com")

        assertThat(testSubject.get("stranger@example.com")).isNotNull()
        assertThat(testSubject.get("stranger@example.com")?.size).isEqualTo(0)
    }

    @Test
    fun `an expired hit should be gone`() {
        var now = 0L
        val testSubject = cache(now = { now })
        testSubject.put("sam@example.com", byteArrayOf(1))

        now += AvatarCache.DEFAULT_HIT_LIFETIME_MILLIS + 1

        assertThat(testSubject.get("sam@example.com")).isNull()
    }

    @Test
    fun `a hit should survive up to its lifetime`() {
        var now = 0L
        val testSubject = cache(now = { now })
        testSubject.put("sam@example.com", byteArrayOf(1))

        now += AvatarCache.DEFAULT_HIT_LIFETIME_MILLIS - 1

        assertThat(testSubject.get("sam@example.com")).isNotNull()
    }

    @Test
    fun `a miss should expire sooner than a hit`() {
        // Someone signing up for Gravatar should show up without waiting out a hit's whole lifetime.
        var now = 0L
        val testSubject = cache(now = { now })
        testSubject.putMiss("stranger@example.com")

        now += AvatarCache.DEFAULT_MISS_LIFETIME_MILLIS + 1

        assertThat(testSubject.get("stranger@example.com")).isNull()
    }

    @Test
    fun `writing twice should keep the newer value`() {
        val testSubject = cache()
        testSubject.put("sam@example.com", byteArrayOf(1))
        testSubject.put("sam@example.com", byteArrayOf(2))

        assertThat(testSubject.get("sam@example.com")?.toList()).isEqualTo(listOf<Byte>(2))
    }

    @Test
    fun `a key that is not a valid file name should still work`() {
        // Keys are addresses and URLs, so they contain characters a file name cannot.
        val testSubject = cache()
        testSubject.put("url:https://example.com/a b/c?d=e", byteArrayOf(7))

        assertThat(testSubject.get("url:https://example.com/a b/c?d=e")?.toList()).isEqualTo(listOf<Byte>(7))
    }

    @Test
    fun `entries should survive a new cache over the same directory`() {
        // The reason this exists at all: the previous caches died with the process.
        cache().put("sam@example.com", byteArrayOf(9))

        assertThat(cache().get("sam@example.com")?.toList()).isEqualTo(listOf<Byte>(9))
    }

    private fun cache(now: () -> Long = { System.currentTimeMillis() }) = AvatarCache(
        context = ApplicationProvider.getApplicationContext(),
        currentTimeMillis = now,
    )
}
