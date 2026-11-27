package com.fsck.k9.message.extractors

import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MimeParameterDecoder
import com.fsck.k9.mail.internet.MimeUtility
import java.io.IOException
import java.io.InputStream

/**
 * Why an attachment is one phishing and malware mail is known to send.
 */
enum class AttachmentRisk {
    /** Opens as a web page in the browser, where a fake sign-in page needs no link to be reached. */
    WEB_PAGE,

    /** Runs as a program, a script or a shortcut to one, on a computer or an Android phone. */
    PROGRAM,

    /** A disk image, which hides the program inside it from the checks made on attachments. */
    DISK_IMAGE,

    /** An Office document that can hold macros, which run programs when allowed. */
    MACRO_DOCUMENT,

    /** An archive locked with a password, so no virus scanner could look inside. */
    LOCKED_ARCHIVE,
}

/**
 * An attachment and why it is risky.
 *
 * @param name the attachment's file name, as the message gives it.
 */
data class RiskyAttachment(val name: String, val risk: AttachmentRisk)

private val WEB_PAGE_EXTENSIONS = setOf("html", "htm", "shtml", "xhtml", "mht", "mhtml", "svg", "svgz")

private val PROGRAM_EXTENSIONS = setOf(
    "exe", "scr", "com", "pif", "bat", "cmd", "msi", "msix", "appx", "js", "jse", "vbs", "vbe", "wsf", "wsh", "hta",
    "ps1", "psm1", "jar", "apk", "xapk", "apks", "lnk", "url", "reg", "cpl", "chm", "scf", "iqy", "slk", "sh",
)

private val DISK_IMAGE_EXTENSIONS = setOf("iso", "img", "vhd", "vhdx", "dmg")

private val MACRO_DOCUMENT_EXTENSIONS = setOf(
    "docm", "dotm", "xlsm", "xltm", "xlsb", "xlam", "pptm", "potm", "ppam", "ppsm", "sldm",
)

private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")

/**
 * Words that give an archive's password in the message's text, as malware mail does to get it past scanners.
 */
private val PASSWORD_WORD = Regex("(?iu)(?<!\\p{L})(?:password|passcode|passwort|kennwort|contraseña|пароль)(?!\\p{L})")

/**
 * How much of the message's text is read for a password.
 */
private const val MAX_TEXT_LENGTH = 8000

/**
 * A zip file starts with a local file header: its signature, then the version, then the flags whose lowest bit says
 * the file is encrypted.
 */
@Suppress("MagicNumber")
private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private const val ZIP_FLAGS_OFFSET = 6
private const val ZIP_HEADER_BYTES = 8
private const val ZIP_ENCRYPTED_FLAG = 0x01

/**
 * The most attachments of one message that are looked at, so a message with thousands of parts opens quickly.
 */
private const val MAX_ATTACHMENTS = 50

/**
 * Says from its file name whether an attachment is of a kind phishing and malware mail is known to send. Archives
 * are not judged here; whether one is locked takes the message, see [findRiskyAttachments].
 *
 * The last extension counts, so `invoice.pdf.exe` is a program and `report.html.pdf` is not a web page.
 */
fun attachmentRisk(fileName: String?): AttachmentRisk? {
    val extension = fileName?.trim()?.trimEnd('.')?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (extension) {
        in WEB_PAGE_EXTENSIONS -> AttachmentRisk.WEB_PAGE
        in PROGRAM_EXTENSIONS -> AttachmentRisk.PROGRAM
        in DISK_IMAGE_EXTENSIONS -> AttachmentRisk.DISK_IMAGE
        in MACRO_DOCUMENT_EXTENSIONS -> AttachmentRisk.MACRO_DOCUMENT
        else -> null
    }
}

/**
 * Finds a message's attachments of a kind phishing and malware mail is known to send: web pages, programs, disk
 * images, Office documents with macros, and archives locked with a password.
 *
 * An archive counts as locked when it is a zip whose first file is encrypted - which needs the attachment on the
 * device - or when the message's text mentions a password, as mail does that sends a locked archive past scanners.
 * Parts the message shows as its own text are not attachments and are not judged.
 *
 * @return the risky attachments, in the order the message has them.
 */
fun findRiskyAttachments(message: Part): List<RiskyAttachment> {
    val attachments = namedParts(message).take(MAX_ATTACHMENTS).toList()
    if (attachments.isEmpty()) return emptyList()

    val mentionsPassword by lazy { mentionsPassword(message) }
    return attachments.mapNotNull { (name, part) ->
        val risk = attachmentRisk(name) ?: if (isArchive(name) && (isEncryptedZip(part) || mentionsPassword)) {
            AttachmentRisk.LOCKED_ARCHIVE
        } else {
            null
        }
        risk?.let { RiskyAttachment(name, it) }
    }
}

private fun namedParts(part: Part): Sequence<Pair<String, Part>> = sequence {
    val body = part.body
    if (body is Multipart) {
        for (child in body.bodyParts) yieldAll(namedParts(child))
    } else {
        fileNameOf(part)?.let { yield(it to part) }
    }
}

private fun fileNameOf(part: Part): String? {
    val fromDisposition = part.disposition?.let { MimeParameterDecoder.decode(it).parameters["filename"] }
    val fromContentType = part.contentType?.let { MimeParameterDecoder.decode(it).parameters["name"] }
    return (fromDisposition ?: fromContentType)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun isArchive(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

private fun mentionsPassword(message: Part): Boolean {
    val text = messageText(message, MAX_TEXT_LENGTH)
    return text != null && PASSWORD_WORD.containsMatchIn(text)
}

@Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
private fun isEncryptedZip(part: Part): Boolean {
    val header = try {
        val body = part.body ?: return false
        MimeUtility.decodeBody(body)?.use { input -> input.readStart(ZIP_HEADER_BYTES) } ?: return false
    } catch (e: IOException) {
        // Not downloaded, or not readable: the password in the text is all there is to go on.
        return false
    } catch (e: RuntimeException) {
        return false
    }
    if (header.size < ZIP_HEADER_BYTES || !header.copyOfRange(0, ZIP_SIGNATURE.size).contentEquals(ZIP_SIGNATURE)) {
        return false
    }
    return header[ZIP_FLAGS_OFFSET].toInt() and ZIP_ENCRYPTED_FLAG != 0
}

/**
 * Reads up to [count] bytes from the start of the stream; fewer when it ends sooner.
 */
private fun InputStream.readStart(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) break
        read += n
    }
    return bytes.copyOf(read)
}
