package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class SenderServerSearchStatusTest {

    @Test
    fun `each folder searched should add its new messages to the count`() {
        val testSubject = SenderServerSearchStatus.Searching(newMessages = 0)

        val result = testSubject.withFolderSearched(3).withFolderSearched(2)

        assertThat(result).isEqualTo(SenderServerSearchStatus.Searching(newMessages = 5))
    }

    @Test
    fun `finishing should keep the count`() {
        val testSubject = SenderServerSearchStatus.Searching(newMessages = 5)

        val result = testSubject.finished()

        assertThat(result).isEqualTo(SenderServerSearchStatus.Finished(newMessages = 5))
        assertThat(result.isRunning).isFalse()
    }

    @Test
    fun `a failure should not be hidden by the end that follows it`() {
        // The search always reports an end, even after it failed, and the failure is what the reader needs to see.
        val testSubject = SenderServerSearchStatus.Failed

        val result = testSubject.finished()

        assertThat(result).isEqualTo(SenderServerSearchStatus.Failed)
    }

    @Test
    fun `a search stopped part way should not be finished by its late reports`() {
        // Leaving the list stops the search to run it again on return; the stopped search still reports in.
        val testSubject = SenderServerSearchStatus.NotStarted

        val result = testSubject.withFolderSearched(4).finished()

        assertThat(result).isEqualTo(SenderServerSearchStatus.NotStarted)
    }

    @Test
    fun `only a search under way should count as running`() {
        assertThat(SenderServerSearchStatus.Searching(newMessages = 0).isRunning).isTrue()
        assertThat(SenderServerSearchStatus.Offline.isRunning).isFalse()
        assertThat(SenderServerSearchStatus.NotStarted.isRunning).isFalse()
    }
}
