package net.thunderbird.backend.graph.command

import com.fsck.k9.mail.Message
import java.io.ByteArrayOutputStream
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.pathSegment
import okio.ByteString.Companion.toByteString

/**
 * Largest MIME message Graph takes in a single request.
 *
 * Graph caps a request at 4 MB, and raw MIME travels base64 encoded, which is a third larger - so 3 MB of message is
 * what fits. Anything bigger goes through [LargeMessageUploader].
 */
internal const val MAX_INLINE_MIME_BYTES = 3 * 1024 * 1024

/**
 * Sends messages and stores them in a folder.
 *
 * Both operations submit the message as raw RFC 5322 content, so the MIME the app composed is delivered unchanged
 * instead of being reconstructed from the Graph JSON message model.
 */
internal class CommandSendMessage(
    private val client: GraphApiClient,
    maxInlineMimeBytes: Int = MAX_INLINE_MIME_BYTES,
) {
    private val maxInlineMimeBytes = maxInlineMimeBytes
    private val largeMessageUploader = LargeMessageUploader(client, maxInlineMimeBytes)

    /**
     * Sends a message. Graph files a copy in Sent Items on the server.
     */
    fun sendMessage(message: Message) {
        val mimeBytes = message.toMimeBytes()

        if (mimeBytes.size <= maxInlineMimeBytes) {
            client.postMime(client.url("me/sendMail"), mimeBytes.toByteString().base64())
        } else {
            largeMessageUploader.send(largeMessageUploader.create(message, folderServerId = null))
        }
    }

    /**
     * Creates a message in a folder from its MIME content, e.g. when saving a draft.
     *
     * @return the server id Graph assigned to the created message.
     */
    fun uploadMessage(folderServerId: String, message: Message): String? {
        val mimeBytes = message.toMimeBytes()
        if (mimeBytes.size > maxInlineMimeBytes) {
            return largeMessageUploader.create(message, folderServerId)
        }

        val url = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages")
        val response = client.postMime(url, mimeBytes.toByteString().base64())

        return client.json.decodeFromString<GraphMessage>(response).id
    }

    /**
     * Encoding to base64 goes through okio rather than `java.util.Base64`, which is only available from API 26.
     */
    private fun Message.toMimeBytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()
}
