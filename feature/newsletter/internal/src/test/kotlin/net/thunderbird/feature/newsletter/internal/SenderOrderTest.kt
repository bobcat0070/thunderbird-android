package net.thunderbird.feature.newsletter.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.feature.newsletter.NewsletterSender

class SenderOrderTest {

    @Test
    fun `senders should be listed by how much mail they have by default`() {
        val testSubject = listOf(sender("a", messages = 2), sender("b", messages = 9), sender("c", messages = 5))

        val result = testSubject.sortedFor(SenderOrder.MOST_MAIL)

        assertThat(result.map { it.address }).containsExactly("b", "c", "a")
    }

    @Test
    fun `least opened should put the senders the reader never opens first`() {
        val testSubject = listOf(
            sender("half", recent = 10, opened = 5),
            sender("quiet", recent = 0, opened = 0),
            sender("never", recent = 6, opened = 0),
            sender("rarely", recent = 10, opened = 1),
        )

        val result = testSubject.sortedFor(SenderOrder.LEAST_OPENED)

        assertThat(result.map { it.address }).containsExactly("never", "rarely", "half", "quiet")
    }

    @Test
    fun `a sender should count as never opened only with enough recent mail`() {
        assertThat(sender("a", recent = 3, opened = 0).isNeverOpened).isTrue()
        assertThat(sender("b", recent = 2, opened = 0).isNeverOpened).isFalse()
        assertThat(sender("c", recent = 8, opened = 1).isNeverOpened).isFalse()
    }

    private fun sender(address: String, messages: Int = 1, recent: Int = 0, opened: Int = 0) = NewsletterSender(
        address = address,
        name = null,
        messageCount = messages,
        latestAt = 0L,
        canUnsubscribe = true,
        recentCount = recent,
        recentOpenedCount = opened,
    )
}
