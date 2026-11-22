package net.thunderbird.feature.attachments.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import net.thunderbird.feature.attachments.StoredAttachment

/**
 * The kinds of file the list can be narrowed to.
 */
internal enum class AttachmentType {
    ALL,
    PDF,
    IMAGES,
    DOCUMENTS,
    OTHER,
}

/**
 * How far back the list goes.
 */
internal enum class AttachmentPeriod(val duration: Duration?) {
    ANY_TIME(null),
    LAST_30_DAYS(30.days),
    LAST_YEAR(365.days),
}

private val DOCUMENT_EXTENSIONS = setOf(
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "csv", "pages", "numbers", "key",
)

private val DOCUMENT_MIME_PREFIXES = listOf(
    "application/msword",
    "application/vnd.ms-",
    "application/vnd.openxmlformats-officedocument.",
    "application/vnd.oasis.opendocument.",
    "application/rtf",
    "text/",
)

/**
 * The kind of file [this] is, from its type when the message gives a useful one and otherwise from its name: mail
 * often labels any file `application/octet-stream`.
 */
internal fun StoredAttachment.type(): AttachmentType {
    val mime = mimeType?.lowercase().orEmpty()
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    return when {
        mime == "application/pdf" || extension == "pdf" -> AttachmentType.PDF

        mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> AttachmentType.IMAGES

        DOCUMENT_MIME_PREFIXES.any {
            mime.startsWith(it)
        } || extension in DOCUMENT_EXTENSIONS -> AttachmentType.DOCUMENTS

        else -> AttachmentType.OTHER
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff")

internal fun List<StoredAttachment>.ofType(type: AttachmentType): List<StoredAttachment> =
    if (type == AttachmentType.ALL) this else filter { it.type() == type }
