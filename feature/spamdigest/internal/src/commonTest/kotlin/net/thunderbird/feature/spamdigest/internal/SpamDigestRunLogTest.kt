package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Instant
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent.Kind

class SpamDigestRunLogTest {
    @Test
    fun `run events should read back as they were written`() {
        val events = listOf(
            SpamDigestRunEvent(Instant.parse("2026-10-12T14:00:00Z"), Kind.SENT, "2026-10-10 \u2013 2026-10-11"),
            SpamDigestRunEvent(Instant.parse("2026-10-12T13:59:00Z"), Kind.STARTED, "1"),
            SpamDigestRunEvent(Instant.parse("2026-10-12T13:58:00Z"), Kind.ALARM),
        )

        val result = decodeRunEvents(encodeRunEvents(events))

        assertThat(result).isEqualTo(events)
    }

    @Test
    fun `a detail should not break the record into more lines`() {
        val event = SpamDigestRunEvent(Instant.parse("2026-10-12T14:00:00Z"), Kind.FAILED, "two\nlines|and a bar")

        val result = decodeRunEvents(encodeRunEvents(listOf(event)))

        assertThat(result).containsExactly(event.copy(detail = "two lines|and a bar"))
    }

    @Test
    fun `lines a newer build wrote should be skipped`() {
        val result = decodeRunEvents("1791900000000|SOMETHING_NEW|x\nnot a line\n1791900000000|ALARM|")

        assertThat(result).containsExactly(SpamDigestRunEvent(Instant.fromEpochMilliseconds(1791900000000), Kind.ALARM))
    }

    @Test
    fun `nothing written should read as no events`() {
        assertThat(decodeRunEvents(null)).isEmpty()
    }

    @Test
    fun `adding an event should keep the newest first, and only so many`() {
        val events = (1..MAX_RUN_EVENTS).map {
            SpamDigestRunEvent(Instant.fromEpochMilliseconds(it.toLong()), Kind.ALARM)
        }
        val newest = SpamDigestRunEvent(Instant.fromEpochMilliseconds(1_000), Kind.SENT)

        val result = addRunEvent(events, newest)

        assertThat(result).hasSize(MAX_RUN_EVENTS)
        assertThat(result.first()).isEqualTo(newest)
    }

    @Test
    fun `a run waiting to start should be kept rather than replaced`() {
        // Replacing it on every mail check could cancel it each time before Android let it run.
        assertThat(shouldStartRun(listOf(PendingRun.WAITING), force = false)).isFalse()
    }

    @Test
    fun `a run should be started when none is waiting, or one is waiting to retry`() {
        assertThat(shouldStartRun(emptyList(), force = false)).isTrue()
        assertThat(shouldStartRun(listOf(PendingRun.FINISHED), force = false)).isTrue()
        assertThat(shouldStartRun(listOf(PendingRun.RETRYING), force = false)).isTrue()
    }

    @Test
    fun `a run under way should always be left to finish`() {
        assertThat(shouldStartRun(listOf(PendingRun.RUNNING), force = false)).isFalse()
        assertThat(shouldStartRun(listOf(PendingRun.RUNNING), force = true)).isFalse()
    }

    @Test
    fun `asking for a digest should replace a run still waiting`() {
        assertThat(shouldStartRun(listOf(PendingRun.WAITING), force = true)).isTrue()
    }
}
