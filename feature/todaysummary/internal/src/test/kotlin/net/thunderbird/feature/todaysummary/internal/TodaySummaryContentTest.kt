package net.thunderbird.feature.todaysummary.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.feature.todaysummary.TodaySummary
import net.thunderbird.feature.todaysummary.WaitingMessage

class TodaySummaryContentTest {

    @Test
    fun `every waiting message should get a line when they fit`() {
        val summary = summary(waiting = messages(3))

        val (shown, more) = waitingToShow(summary)

        assertThat(shown).isEqualTo(messages(3))
        assertThat(more).isEqualTo(0)
    }

    @Test
    fun `the most recent waiting messages should fill the lines and the rest be counted`() {
        val summary = summary(waiting = messages(8))

        val (shown, more) = waitingToShow(summary)

        assertThat(shown).isEqualTo(messages(8).take(MAX_LINES))
        assertThat(more).isEqualTo(8 - MAX_LINES)
    }

    @Test
    fun `parcels and codes should keep a line each`() {
        val summary = summary(waiting = messages(8), parcels = listOf("Harbor Books"), codeCount = 1)

        val (shown, more) = waitingToShow(summary)

        assertThat(shown.size).isEqualTo(MAX_LINES - 2)
        assertThat(more).isEqualTo(8 - (MAX_LINES - 2))
    }

    @Test
    fun `one message waiting should open that message`() {
        val summary = summary(waiting = messages(1), parcels = listOf("Harbor Books"), codeCount = 2)

        assertThat(tapTarget(summary)).isEqualTo(TapTarget.MESSAGE)
    }

    @Test
    fun `several messages waiting should open the inbox`() {
        assertThat(tapTarget(summary(waiting = messages(2)))).isEqualTo(TapTarget.INBOX)
    }

    @Test
    fun `parcels alone should open Packages`() {
        assertThat(tapTarget(summary(parcels = listOf("Harbor Books")))).isEqualTo(TapTarget.PACKAGES)
    }

    @Test
    fun `codes without messages waiting should open the inbox`() {
        assertThat(tapTarget(summary(parcels = listOf("Harbor Books"), codeCount = 1))).isEqualTo(TapTarget.INBOX)
    }

    private fun summary(
        waiting: List<WaitingMessage> = emptyList(),
        parcels: List<String> = emptyList(),
        codeCount: Int = 0,
    ) = TodaySummary(
        parcels = parcels,
        waiting = waiting,
        codeCount = codeCount,
        codeSenders = List(codeCount) { "Lumen" },
    )

    private fun messages(count: Int) = List(count) { index ->
        WaitingMessage(
            sender = "Sender $index",
            subject = "Subject $index",
            accountId = "account",
            folderId = 1L,
            messageServerId = "uid-$index",
        )
    }
}
