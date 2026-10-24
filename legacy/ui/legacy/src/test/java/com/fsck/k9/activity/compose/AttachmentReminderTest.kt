package com.fsck.k9.activity.compose

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class AttachmentReminderTest {
    private val keywords = "attach, enclos"

    @Test
    fun `a message saying something is attached should be noticed`() {
        assertThat(AttachmentReminder.mentionsAttachment("Hi Jana,\nThe invoice is attached.", keywords)).isTrue()
        assertThat(AttachmentReminder.mentionsAttachment("See the ATTACHMENT for details", keywords)).isTrue()
        assertThat(AttachmentReminder.mentionsAttachment("Please find enclosed the forms", keywords)).isTrue()
    }

    @Test
    fun `a message that does not mention one should not be noticed`() {
        assertThat(AttachmentReminder.mentionsAttachment("See you at 5.", keywords)).isFalse()
        assertThat(AttachmentReminder.mentionsAttachment("", keywords)).isFalse()
    }

    @Test
    fun `a keyword inside another word should not count`() {
        assertThat(AttachmentReminder.mentionsAttachment("We reattached nothing; the reattachment was fine", keywords))
            .isFalse()
    }

    @Test
    fun `quoted lines should not count`() {
        val text = "Thanks, got it.\n\n> I have attached the report.\n>> attachment two"

        assertThat(AttachmentReminder.mentionsAttachment(text, keywords)).isFalse()
    }

    @Test
    fun `an empty keyword list should notice nothing`() {
        assertThat(AttachmentReminder.mentionsAttachment("attached", " , ")).isFalse()
    }

    @Test
    fun `keywords in other alphabets should be matched`() {
        assertThat(AttachmentReminder.mentionsAttachment("Смотрите вложение", "вложен")).isTrue()
    }
}
