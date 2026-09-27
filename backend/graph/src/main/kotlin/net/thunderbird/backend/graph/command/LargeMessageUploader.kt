package net.thunderbird.backend.graph.command

import com.fsck.k9.mail.Body
import com.fsck.k9.mail.BodyPart
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.internet.MimeParameterDecoder
import com.fsck.k9.mail.internet.MimeUtility
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.core.common.exception.MessagingException
import okio.ByteString.Companion.toByteString

/**
 * Largest attachment added to a draft in one request. Anything bigger goes through an upload session; this leaves the
 * base64 form of the request under Graph's 4 MB cap.
 */
internal const val MAX_DIRECT_ATTACHMENT_BYTES = 2 * 1024 * 1024

/**
 * Size of each piece sent to an upload session. Graph requires a multiple of 320 KiB.
 */
private const val UPLOAD_CHUNK_BYTES = 320 * 1024 * 10

private const val DEFAULT_ATTACHMENT_NAME = "attachment"

/**
 * Creates a message too large for Graph to take in one request.
 *
 * Graph accepts raw MIME only up to 4 MB, and has no way to upload more of it in pieces. Its upload sessions take
 * attachments, though, and a draft created from MIME keeps every header the app wrote - the ones threading a reply,
 * too. So the message is created without its attachments, largest first until what is left fits, and those are then
 * added to the draft: the small ones in a request each, the rest through an upload session in pieces.
 *
 * If anything fails after the draft exists, the draft is deleted rather than left behind half built.
 */
internal class LargeMessageUploader(
    private val client: GraphApiClient,
    private val maxInlineMimeBytes: Int,
) {
    /**
     * @param folderServerId the folder to create the message in, or `null` for Drafts - where a message about to
     *   be sent is created.
     * @return the id Graph gave the complete message.
     */
    fun create(message: Message, folderServerId: String?): String {
        val copy = MimeMessage.parseMimeMessage(message.toMimeBytes().inputStream(), true)
        val detached = detachUntilFits(copy)
        val reducedMime = copy.toMimeBytes()
        if (reducedMime.size > maxInlineMimeBytes) {
            throw MessagingException("The message text is too large to send via Microsoft Graph", true, null)
        }

        val url = if (folderServerId == null) {
            client.url("me/messages")
        } else {
            client.url("me/mailFolders/${pathSegment(folderServerId)}/messages")
        }
        val messageId = client.json.decodeFromString<GraphMessage>(
            client.postMime(url, reducedMime.toByteString().base64()),
        ).id

        try {
            detached.forEach { attachment -> addAttachment(messageId, attachment) }
        } catch (e: MessagingException) {
            discardDraft(messageId)
            throw e
        }

        return messageId
    }

    /**
     * Sends a draft created by [create]. Graph files it in Sent Items, as for any other message it sends.
     */
    fun send(messageId: String) {
        try {
            client.postJson(client.url("me/messages/${pathSegment(messageId)}/send"), "")
        } catch (e: MessagingException) {
            discardDraft(messageId)
            throw e
        }
    }

    /**
     * Takes attachments out of [message], largest first, until what remains fits in one request.
     */
    private fun detachUntilFits(message: MimeMessage): List<DetachedAttachment> {
        val candidates = message.attachmentParts()
            .map { part -> part to part.decodedBytes() }
            .sortedByDescending { (_, bytes) -> bytes.size }

        val detached = mutableListOf<DetachedAttachment>()
        for ((part, bytes) in candidates) {
            if (message.toMimeBytes().size <= maxInlineMimeBytes) break

            message.removePart(part)
            detached += DetachedAttachment(
                name = part.fileName() ?: DEFAULT_ATTACHMENT_NAME,
                contentType = part.mimeType ?: "application/octet-stream",
                contentId = part.contentId,
                isInline = part.disposition?.trim()?.startsWith("inline", ignoreCase = true) == true,
                bytes = bytes,
            )
        }

        return detached
    }

    private fun addAttachment(messageId: String, attachment: DetachedAttachment) {
        val attachmentsPath = "me/messages/${pathSegment(messageId)}/attachments"

        if (attachment.bytes.size <= MAX_DIRECT_ATTACHMENT_BYTES) {
            val body = buildJsonObject {
                put("@odata.type", "#microsoft.graph.fileAttachment")
                put("name", attachment.name)
                put("contentType", attachment.contentType)
                put("isInline", attachment.isInline)
                attachment.contentId?.let { put("contentId", it) }
                put("contentBytes", attachment.bytes.toByteString().base64())
            }
            client.postJson(client.url(attachmentsPath), body.toString())
            return
        }

        val sessionRequest = buildJsonObject {
            put(
                "AttachmentItem",
                buildJsonObject {
                    put("attachmentType", "file")
                    put("name", attachment.name)
                    put("size", attachment.bytes.size)
                    put("contentType", attachment.contentType)
                    put("isInline", attachment.isInline)
                    attachment.contentId?.let { put("contentId", it) }
                },
            )
        }
        val session = client.json.parseToJsonElement(
            client.postJson(client.url("$attachmentsPath/createUploadSession"), sessionRequest.toString()),
        ).jsonObject
        val uploadUrl = client.uploadUrl(
            session["uploadUrl"]?.jsonPrimitive?.content
                ?: throw MessagingException("Microsoft Graph returned no upload URL", false, null),
        )

        val total = attachment.bytes.size
        var offset = 0
        while (offset < total) {
            val end = minOf(offset + UPLOAD_CHUNK_BYTES, total)
            client.putUploadChunk(
                uploadUrl,
                attachment.bytes.copyOfRange(offset, end),
                "bytes $offset-${end - 1}/$total",
            )
            offset = end
        }
    }

    @Suppress("SwallowedException")
    private fun discardDraft(messageId: String) {
        try {
            client.delete(client.url("me/messages/${pathSegment(messageId)}"))
        } catch (e: MessagingException) {
            // Best effort: what the caller needs to hear about is the failure that got here, not this one.
        }
    }

    private class DetachedAttachment(
        val name: String,
        val contentType: String,
        val contentId: String?,
        val isInline: Boolean,
        val bytes: ByteArray,
    )
}

/**
 * The parts of a message that are files rather than its text: every leaf with a file name.
 */
private fun Part.attachmentParts(): List<BodyPart> {
    val multipart = body as? Multipart ?: return emptyList()

    return multipart.bodyParts.flatMap { part ->
        when {
            part.body is Multipart -> part.attachmentParts()
            part.fileName() != null -> listOf(part)
            else -> emptyList()
        }
    }
}

private fun Part.fileName(): String? {
    val fromDisposition = disposition?.let { MimeParameterDecoder.decode(it).parameters["filename"] }
    val fromContentType = contentType?.let { MimeParameterDecoder.decode(it).parameters["name"] }

    return (fromDisposition ?: fromContentType)?.takeIf { it.isNotBlank() }
}

private fun Part.decodedBytes(): ByteArray = MimeUtility.decodeBody(body).use { it.readBytes() }

/**
 * Replaces the multipart holding [target] with one holding everything else. A multipart offers no way to remove a
 * part, and rebuilding it keeps its type and boundary, so the headers above it still describe it.
 *
 * Found by walking down from here, because a parsed multipart does not know which part holds it.
 */
private fun Part.removePart(target: BodyPart) {
    val multipart = body as? MimeMultipart ?: return

    if (multipart.bodyParts.any { it === target }) {
        val rebuilt = MimeMultipart(multipart.mimeType, multipart.boundary).apply {
            preamble = multipart.preamble
            epilogue = multipart.epilogue
        }
        multipart.bodyParts.filter { it !== target }.forEach(rebuilt::addBodyPart)
        body = rebuilt as Body
    } else {
        multipart.bodyParts.forEach { it.removePart(target) }
    }
}

private fun Message.toMimeBytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()
