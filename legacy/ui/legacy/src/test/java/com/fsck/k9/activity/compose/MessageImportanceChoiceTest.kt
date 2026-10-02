package com.fsck.k9.activity.compose

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.MessageImportance
import org.junit.Test

class MessageImportanceChoiceTest {

    @Test
    fun `every importance should be found at the position it is listed at`() {
        for (importance in MessageImportance.entries) {
            val index = MessageImportanceChoice.indexOf(importance)

            assertThat(MessageImportanceChoice.at(index)).isEqualTo(importance)
        }
    }

    @Test
    fun `a position outside the list should be normal importance`() {
        assertThat(MessageImportanceChoice.at(-1)).isEqualTo(MessageImportance.NORMAL)
        assertThat(MessageImportanceChoice.at(3)).isEqualTo(MessageImportance.NORMAL)
    }

    @Test
    fun `a saved importance should be restored`() {
        assertThat(MessageImportanceChoice.fromSavedState("HIGH")).isEqualTo(MessageImportance.HIGH)
        assertThat(MessageImportanceChoice.fromSavedState("LOW")).isEqualTo(MessageImportance.LOW)
    }

    @Test
    fun `state without an importance should restore as normal`() {
        assertThat(MessageImportanceChoice.fromSavedState(null)).isEqualTo(MessageImportance.NORMAL)
        assertThat(MessageImportanceChoice.fromSavedState("unknown")).isEqualTo(MessageImportance.NORMAL)
    }
}
