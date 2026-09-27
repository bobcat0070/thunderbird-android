package com.fsck.k9.controller

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class UndoSendHoldTest {
    private val scheduled = mutableListOf<Runnable>()
    private var sends = 0

    @Test
    fun `nothing should be held when undo send is off`() {
        val testSubject = UndoSendHold(delaySeconds = { 0 }, schedule = { _, action -> scheduled += action })

        assertThat(testSubject.hold("account", 1L) { sends++ }).isFalse()
        assertThat(testSubject.isHeld("account", 1L)).isFalse()
    }

    @Test
    fun `a held message should be sent once the delay passes`() {
        val testSubject = holdFor(seconds = 10)
        testSubject.hold("account", 1L) { sends++ }
        assertThat(testSubject.isHeld("account", 1L)).isTrue()

        scheduled.single().run()

        assertThat(sends).isEqualTo(1)
        assertThat(testSubject.isHeld("account", 1L)).isFalse()
    }

    @Test
    fun `a message taken back should never be sent`() {
        val testSubject = holdFor(seconds = 10)
        testSubject.hold("account", 1L) { sends++ }

        assertThat(testSubject.cancel("account", 1L)).isTrue()
        scheduled.single().run()

        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `a message already released should not be taken back`() {
        val testSubject = holdFor(seconds = 10)
        testSubject.hold("account", 1L) { sends++ }
        scheduled.single().run()

        assertThat(testSubject.cancel("account", 1L)).isFalse()
    }

    @Test
    fun `holding should say when the message will go`() {
        val testSubject = UndoSendHold(
            delaySeconds = { 5 },
            currentTimeMillis = { 1_000L },
            schedule = { _, action -> scheduled += action },
        )

        testSubject.hold("account", 7L) { }

        assertThat(testSubject.heldMessages.value)
            .isEqualTo(listOf(UndoSendHold.HeldMessage("account", 7L, releaseAtMillis = 6_000L)))
    }

    private fun holdFor(seconds: Int) = UndoSendHold(
        delaySeconds = { seconds },
        schedule = { _, action -> scheduled += action },
    )
}
