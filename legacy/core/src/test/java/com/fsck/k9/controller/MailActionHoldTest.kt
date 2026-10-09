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
    private var now = NOW
    private val testSubject = MailActionHold(
        delayMillis = DELAY_MILLIS,
        currentTimeMillis = { now },
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
        assertThat(testSubject.heldActions.value).isEmpty()
    }

    @Test
    fun `mail should stay hidden after the wait until the action has taken it away`() {
        // The action is queued behind whatever the app is doing; shown again in between, the mail flashed back
        // into the list just as "Undo" went away.
        testSubject.hold(MailActionHold.Kind.Delete, listOf(MESSAGE)) { }
        runScheduled()

        testSubject.forgetReleased { false }

        assertThat(testSubject.isHeld(MESSAGE)).isTrue()
    }

    @Test
    fun `mail should no longer be hidden once it is gone from where it was`() {
        // So that mail moved back there later is shown.
        testSubject.hold(MailActionHold.Kind.Move(folderId = 7), listOf(MESSAGE)) { }
        runScheduled()

        testSubject.forgetReleased { message -> message == MESSAGE }

        assertThat(testSubject.isHeld(MESSAGE)).isFalse()
    }

    @Test
    fun `mail whose action never took effect should come back in the end`() {
        testSubject.hold(MailActionHold.Kind.Archive, listOf(MESSAGE)) { }
        runScheduled()
        now += 120_000L

        testSubject.forgetReleased { false }

        assertThat(testSubject.isHeld(MESSAGE)).isFalse()
    }

    @Test
    fun `releasing everything should keep the mail hidden until it is gone`() {
        testSubject.hold(MailActionHold.Kind.Delete, listOf(MESSAGE)) { }

        testSubject.releaseAll()

        assertThat(testSubject.heldActions.value).isEmpty()
        assertThat(testSubject.isHeld(MESSAGE)).isTrue()
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
        assertThat(testSubject.isHeld(MESSAGE)).isFalse()
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
