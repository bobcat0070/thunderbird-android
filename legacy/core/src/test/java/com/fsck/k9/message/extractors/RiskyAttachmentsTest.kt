package com.fsck.k9.message.extractors

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.mail.testing.message.buildMessage
import com.fsck.k9.mailstore.BinaryMemoryBody
import kotlin.test.Test

class RiskyAttachmentsTest {
    @Test
    fun `file names should be judged by their last extension`() {
        assertThat(attachmentRisk("invoice.html")).isEqualTo(AttachmentRisk.WEB_PAGE)
        assertThat(attachmentRisk("Scan_0042.SVG")).isEqualTo(AttachmentRisk.WEB_PAGE)
        assertThat(attachmentRisk("invoice.pdf.exe")).isEqualTo(AttachmentRisk.PROGRAM)
        assertThat(attachmentRisk("update.apk")).isEqualTo(AttachmentRisk.PROGRAM)
        assertThat(attachmentRisk("Statement.lnk")).isEqualTo(AttachmentRisk.PROGRAM)
        assertThat(attachmentRisk("shipping-docs.iso")).isEqualTo(AttachmentRisk.DISK_IMAGE)
        assertThat(attachmentRisk("Payroll.xlsm")).isEqualTo(AttachmentRisk.MACRO_DOCUMENT)
    }

    @Test
    fun `ordinary files should not be risky`() {
        assertThat(attachmentRisk("report.html.pdf")).isNull()
        assertThat(attachmentRisk("photo.jpg")).isNull()
        assertThat(attachmentRisk("Contract.docx")).isNull()
        assertThat(attachmentRisk("archive.zip")).isNull()
        assertThat(attachmentRisk("README")).isNull()
        assertThat(attachmentRisk(null)).isNull()
    }

    @Test
    fun `risky attachments should be found in the message`() {
        val testSubject = messageWithAttachments(
            text = "Please see the attached invoice.",
            "invoice.html" to "text/html",
            "photo.jpg" to "image/jpeg",
            "tool.exe" to "application/octet-stream",
        )

        val risky = findRiskyAttachments(testSubject)

        assertThat(risky).containsExactly(
            RiskyAttachment("invoice.html", AttachmentRisk.WEB_PAGE),
            RiskyAttachment("tool.exe", AttachmentRisk.PROGRAM),
        )
    }

    @Test
    fun `the message's own html should not count as an attachment`() {
        val testSubject = buildMessage {
            multipart("alternative") {
                bodyPart("text/plain; charset=utf-8") { textBody("Hello") }
                bodyPart("text/html; charset=utf-8") { textBody("<p>Hello</p>") }
            }
        }

        assertThat(findRiskyAttachments(testSubject)).isEmpty()
    }

    @Test
    fun `an archive whose password is in the text should be a locked archive`() {
        val testSubject = messageWithAttachments(
            text = "The documents are in the archive. Password: 4521",
            "documents.zip" to "application/zip",
        )

        assertThat(findRiskyAttachments(testSubject))
            .containsExactly(RiskyAttachment("documents.zip", AttachmentRisk.LOCKED_ARCHIVE))
    }

    @Test
    fun `an archive with no password should be left alone`() {
        val testSubject = messageWithAttachments(
            text = "Here are the photos from the trip.",
            "photos.zip" to "application/zip",
        )

        assertThat(findRiskyAttachments(testSubject)).isEmpty()
    }

    @Test
    fun `an encrypted zip should be a locked archive whatever the text says`() {
        val encryptedZipStart = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x01, 0x00, 0x08, 0x00)
        val testSubject = messageWithZip(name = "files.zip", bytes = encryptedZipStart)

        assertThat(findRiskyAttachments(testSubject))
            .containsExactly(RiskyAttachment("files.zip", AttachmentRisk.LOCKED_ARCHIVE))
    }

    @Test
    fun `an unencrypted zip should be left alone`() {
        val plainZipStart = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00)
        val testSubject = messageWithZip(name = "files.zip", bytes = plainZipStart)

        assertThat(findRiskyAttachments(testSubject)).isEmpty()
    }

    private fun messageWithAttachments(text: String, vararg attachments: Pair<String, String>) = buildMessage {
        multipart("mixed") {
            bodyPart("text/plain; charset=utf-8") { textBody(text) }
            for ((name, mimeType) in attachments) {
                bodyPart("$mimeType; name=\"$name\"") {
                    header("Content-Disposition", "attachment; filename=\"$name\"")
                    textBody("x")
                }
            }
        }
    }

    private fun messageWithZip(name: String, bytes: ByteArray): Message {
        val text = MimeBodyPart().apply {
            addHeader("Content-Type", "text/plain; charset=utf-8")
            MimeMessageHelper.setBody(this, TextBody("Here are the files."))
        }
        val zip = MimeBodyPart().apply {
            addHeader("Content-Type", "application/zip; name=\"$name\"")
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            MimeMessageHelper.setBody(this, BinaryMemoryBody(bytes, "binary"))
        }
        val multipart = MimeMultipart.newInstance().apply {
            setSubType("mixed")
            addBodyPart(text)
            addBodyPart(zip)
        }
        return MimeMessage().apply { MimeMessageHelper.setBody(this, multipart) }
    }
}
