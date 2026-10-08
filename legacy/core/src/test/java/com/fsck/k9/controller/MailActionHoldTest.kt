package com.fsck.k9.controller

import app.k9mail.legacy.message.controller.MessageReference
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class MailActionHoldTest {
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()
    private val testSubject = MailActionHold(
        delayMillis = DELAY_MILLIS,
        currentTimeMillis = { NOW },
        schedule = { delayMillis, action -> scheduled += delayMillis to action },
    )

    @Test
    fun `held mail should be left out of the lists until the wait is over`() {
        testSubject.hold(MailActionHold.Kind.Delete, listOf(MESSAGE)) { }

        assertThat(testSubject.isHeld(MESSAGE)).isTrue()
        assertThat(testSubject.heldActions.value.single().releaseAtMillis).isEqualTo(NOW + DELAY_MILLIS)
    }

    @Test
    fun `the action should be carried out once the wait is over`() {
        var performed = 0
        testSubject.hold(MailActionHold.Kind.Archive, listOf(MESSAGE)) { performed++ }

        runScheduled()

        assertThat(performed).isEqualTo(1)
        assertThat(testSubject.isHeld(MESSAGE)).isFalse()
    }

    @Test
    fun `undo should drop the action and put the mail back`() {
        var performed = 0
        val action = testSubject.hold(MailActionHold.Kind.Move(folderId = 7), listOf(MESSAGE)) { performed++ }

        val result = testSubject.undo(action.id)
        runScheduled()

        assertThat(result).isTrue()
        assertThat(performed).isEqualTo(0)
        assertThat(testSubject.heldActions.value).isEmpty()
    }

    @Test
    fun `undo after the wait should say it is too late`() {
        var performed = 0
        val action = testSubject.hold(MailActionHold.Kind.Delete, listOf(MESSAGE)) { performed++ }
        runScheduled()

        val result = testSubject.undo(action.id)

        assertThat(result).isFalse()
        assertThat(performed).isEqualTo(1)
    }

    @Test
    fun `undo should take back only the action it was offered for`() {
        val first = testSubject.hold(MailActionHold.Kind.Delete, listOf(MESSAGE)) { }
        testSubject.hold(MailActionHold.Kind.Spam, listOf(OTHER_MESSAGE)) { }

        testSubject.undo(first.id)

        assertThat(testSubject.heldActions.value.map { it.kind }).containsExactly(MailActionHold.Kind.Spam)
    }

    @Test
    fun `releasing should carry out everything waiting, once`() {
        // For when the screen offering "Undo" goes away: left waiting, the mail would just come back.
        var performed = 0
        testSubject.hold(MailActionHold.Kind.Delete, listOf(MESSAGE)) { performed++ }
        testSubject.hold(MailActionHold.Kind.Archive, listOf(OTHER_MESSAGE)) { performed++ }

        testSubject.releaseAll()
        runScheduled()

        assertThat(performed).isEqualTo(2)
        assertThat(testSubject.heldActions.value).isEmpty()
    }

    private fun runScheduled() {
        val actions = scheduled.toList()
        scheduled.clear()
        actions.forEach { (_, action) -> action.run() }
    }

    private companion object {
        const val DELAY_MILLIS = 3_000L
        const val NOW = 1_000_000L
        val MESSAGE = MessageReference("account", 1L, "uid1")
        val OTHER_MESSAGE = MessageReference("account", 2L, "uid2")
    }
}
