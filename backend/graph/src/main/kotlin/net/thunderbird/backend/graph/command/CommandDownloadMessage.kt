package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.toEnvelopeMessage

/**
 * Downloads message content from Microsoft Graph.
 *
 * Full messages are retrieved as raw RFC 5322 content through the `$value` endpoint, which lets the existing MIME
 * parser handle the message exactly as it would for IMAP. The Graph JSON representation is only used for envelopes,
 * because it cannot round-trip arbitrary MIME structures.
 */
internal class CommandDownloadMessage(
    private val backendStorage: BackendStorage,
    private val client: GraphApiClient,
) {
    fun downloadMessageStructure(folderServerId: String, messageServerId: String) {
        val message = fetchEnvelope(messageServerId)

        backendStorage.getFolder(folderServerId).saveMessage(message, MessageDownloadState.ENVELOPE)
    }

    fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        val message = fetchFullMessage(messageServerId)

        backendStorage.getFolder(folderServerId).saveMessage(message, MessageDownloadState.FULL)
    }

    /**
     * Downloads the raw MIME content of a message and parses it.
     */
    fun fetchFullMessage(messageServerId: String): MimeMessage {
        val url = client.url("me/messages/$messageServerId/\$value")

        val message = client.getStream(url) { inputStream ->
            MimeMessage.parseMimeMessage(inputStream, false)
        }
        message.uid = messageServerId

        return message
    }

    private fun fetchEnvelope(messageServerId: String): MimeMessage {
        val url = client.url("me/messages/$messageServerId") {
            addQueryParameter("\$select", MESSAGE_ENVELOPE_SELECT)
        }

        val graphMessage = client.json.decodeFromString<GraphMessage>(client.getString(url))

        return graphMessage.toEnvelopeMessage()
    }
}
