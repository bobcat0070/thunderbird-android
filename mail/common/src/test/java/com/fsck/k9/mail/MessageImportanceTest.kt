package com.fsck.k9.mail

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.internet.MimeMessage
import org.junit.Test

class MessageImportanceTest {

    @Test
    fun `Importance header should decide the importance`() {
        assertThat(MessageImportance.fromHeaders(importance = "high", priority = null))
            .isEqualTo(MessageImportance.HIGH)
        assertThat(MessageImportance.fromHeaders(importance = " Low ", priority = null))
            .isEqualTo(MessageImportance.LOW)
        assertThat(MessageImportance.fromHeaders(importance = "Normal", priority = null))
            .isEqualTo(MessageImportance.NORMAL)
    }

    @Test
    fun `Importance header should win over X-Priority`() {
        val result = MessageImportance.fromHeaders(importance = "normal", priority = "1 (Highest)")

        assertThat(result).isEqualTo(MessageImportance.NORMAL)
    }

    @Test
    fun `X-Priority should decide when there is no Importance header`() {
        assertThat(MessageImportance.fromHeaders(importance = null, priority = "1 (Highest)"))
            .isEqualTo(MessageImportance.HIGH)
        assertThat(MessageImportance.fromHeaders(importance = null, priority = "2"))
            .isEqualTo(MessageImportance.HIGH)
        assertThat(MessageImportance.fromHeaders(importance = "", priority = "3"))
            .isEqualTo(MessageImportance.NORMAL)
        assertThat(MessageImportance.fromHeaders(importance = null, priority = "4"))
            .isEqualTo(MessageImportance.LOW)
        assertThat(MessageImportance.fromHeaders(importance = null, priority = "5 (Lowest)"))
            .isEqualTo(MessageImportance.LOW)
    }

    @Test
    fun `a message without either header should be of normal importance`() {
        assertThat(MessageImportance.fromHeaders(importance = null, priority = null))
            .isEqualTo(MessageImportance.NORMAL)
        assertThat(MessageImportance.fromHeaders(importance = null, priority = "urgent"))
            .isEqualTo(MessageImportance.NORMAL)
    }

    @Test
    fun `importance set on a message should be read back from it`() {
        val message = MimeMessage()

        message.setImportance(MessageImportance.HIGH)

        assertThat(message.importance).isEqualTo(MessageImportance.HIGH)
        assertThat(message.getHeader("Importance").toList()).isEqualTo(listOf("high"))
        assertThat(message.getHeader("X-Priority").toList()).isEqualTo(listOf("1"))
    }

    @Test
    fun `setting normal importance should remove the headers`() {
        val message = MimeMessage().apply {
            addHeader("Importance", "high")
            addHeader("X-Priority", "1")
        }

        message.setImportance(MessageImportance.NORMAL)

        assertThat(message.importance).isEqualTo(MessageImportance.NORMAL)
        assertThat(message.getHeader("Importance").toList()).isEmpty()
        assertThat(message.getHeader("X-Priority").toList()).isEmpty()
    }
}
