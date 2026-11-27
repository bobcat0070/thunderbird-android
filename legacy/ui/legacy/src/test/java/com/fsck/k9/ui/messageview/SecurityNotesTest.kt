package com.fsck.k9.ui.messageview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.message.extractors.AttachmentRisk
import com.fsck.k9.message.extractors.RiskyAttachment
import org.junit.Test

private const val SERVER_ID = "mx.mail.example"
private const val SENDER = "no-reply@lumen.example"
private const val DMARC_FAIL = "$SERVER_ID; dmarc=fail header.from=lumen.example"
private const val DMARC_PASS = "$SERVER_ID; dmarc=pass header.from=lumen.example"

class SecurityNotesTest : K9RobolectricTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a security alert should advise going to the site directly`() {
        val note = SecurityNotes.securityAlertNote(
            context,
            message(subject = "Reset your password"),
            SENDER,
            listOf(DMARC_PASS),
            SERVER_ID,
        )

        assertThat(note).isNotNull().prop(SecurityNotes.Note::isWarning).isFalse()
        assertThat(note?.text).isEqualTo(
            "Password reset at lumen.example. If you didn't ask for it, go to lumen.example yourself, " +
                "not through this mail's links.",
        )
    }

    @Test
    fun `a security alert that failed its sender check should be a warning`() {
        val note = SecurityNotes.securityAlertNote(
            context,
            message(subject = "New sign-in to your account"),
            SENDER,
            listOf(DMARC_FAIL),
            SERVER_ID,
        )

        assertThat(note).isNotNull().prop(SecurityNotes.Note::isWarning).isTrue()
    }

    @Test
    fun `a mailing list post about passwords should get no note`() {
        val message = message(subject = "Re: Reset your password page").apply {
            addHeader("List-Id", "<dev.lists.example>")
        }

        assertThat(SecurityNotes.securityAlertNote(context, message, SENDER, emptyList(), SERVER_ID)).isNull()
    }

    @Test
    fun `a breach notice should name the plus address the sender has`() {
        val text = SecurityNotes.breachNoticeText(context, SENDER, listOf("you+lumen@mail.example"))

        assertThat(text).isEqualTo(
            "Data breach notice from lumen.example, which has your address you+lumen@mail.example. Change your " +
                "password there, and wherever you used the same one.",
        )
    }

    @Test
    fun `a breach notice that failed its sender check should not be pointed out`() {
        assertThat(SecurityNotes.mayShowBreachNotice(SENDER, listOf(DMARC_FAIL), SERVER_ID)).isFalse()
        assertThat(SecurityNotes.mayShowBreachNotice(SENDER, emptyList(), SERVER_ID)).isTrue()
    }

    @Test
    fun `several risky attachments should name the first and count the rest`() {
        val text = SecurityNotes.riskyAttachmentsText(
            context,
            listOf(
                RiskyAttachment("invoice.html", AttachmentRisk.WEB_PAGE),
                RiskyAttachment("tool.exe", AttachmentRisk.PROGRAM),
            ),
        )

        assertThat(text).isEqualTo(
            "Risky attachment: invoice.html opens as a web page, the way fake sign-in pages are sent. And 1 more.",
        )
    }

    private fun message(subject: String) = MimeMessage().apply { this.subject = subject }
}
