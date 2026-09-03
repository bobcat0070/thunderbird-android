package net.thunderbird.backend.graph.command

import com.fsck.k9.mail.Message
import java.io.ByteArrayOutputStream
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.core.common.exception.MessagingException
import okio.ByteString.Companion.toByteString

/**
 * Largest MIME payload Graph accepts inline for `sendMail` and message creation.
 *
 * Larger messages need an upload session, which this backend does not implement yet.
 */
private const val MAX_INLINE_MIME_BYTES = 4 * 1024 * 1024

/**
 * Sends messages and stores them in a folder.
 *
 * Both operations submit the message as raw RFC 5322 content, so the MIME the app composed is delivered unchanged
 * instead of being reconstructed from the Graph JSON message model.
 */
internal class CommandSendMessage(
    private val client: GraphApiClient,
) {
    /**
     * Sends a message. Graph files a copy in Sent Items on the server.
     */
    fun sendMessage(message: Message) {
        val url = client.url("me/sendMail")

        client.postMime(url, message.toBase64Mime())
    }

    /**
     * Creates a message in a folder from its MIME content, e.g. when saving a draft.
     *
     * @return the server id Graph assigned to the created message.
     */
    fun uploadMessage(folderServerId: String, message: Message): String? {
        val url = client.url("me/mailFolders/$folderServerId/messages")
        val response = client.postMime(url, message.toBase64Mime())

        return client.json.decodeFromString<GraphMessage>(response).id
    }

    /**
     * Serializes a message to base64 encoded MIME, which is the format Graph expects for raw content.
     *
     * Encoding goes through okio rather than `java.util.Base64`, which is only available from API 26.
     */
    private fun Message.toBase64Mime(): String {
        val outputStream = ByteArrayOutputStream()
        writeTo(outputStream)
        val mimeBytes = outputStream.toByteArray()

        if (mimeBytes.size > MAX_INLINE_MIME_BYTES) {
            throw MessagingException(
                "Message is too large to send via Microsoft Graph without an upload session",
                true,
                null,
            )
        }

        return mimeBytes.toByteString().base64()
    }
}
