package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.MESSAGE_ENVELOPE_EXPAND
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.backend.graph.api.setServerRelevance
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
    suspend fun downloadMessageStructure(folderServerId: String, messageServerId: String) {
        val message = fetchEnvelope(messageServerId)

        backendStorage.getFolder(folderServerId).saveMessage(message, MessageDownloadState.ENVELOPE)
    }

    suspend fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        val message = fetchFullMessage(messageServerId)
        // The raw message says nothing about Focused Inbox, and the stored message is classified again from what
        // is saved here, so where it was sorted is asked for alongside - or opening it would change its category.
        message.setServerRelevance(fetchInferenceClassification(messageServerId))

        backendStorage.getFolder(folderServerId).saveMessage(message, MessageDownloadState.FULL)
    }

    /**
     * Downloads the raw MIME content of a message and parses it.
     */
    fun fetchFullMessage(messageServerId: String): MimeMessage {
        val url = client.url("me/messages/${pathSegment(messageServerId)}/\$value")

        val message = client.getStream(url) { inputStream ->
            MimeMessage.parseMimeMessage(inputStream, false)
        }
        message.uid = messageServerId

        return message
    }

    private fun fetchInferenceClassification(messageServerId: String): String? {
        val url = client.url("me/messages/${pathSegment(messageServerId)}") {
            addQueryParameter("\$select", "inferenceClassification")
        }

        return client.json.decodeFromString<GraphMessage>(client.getString(url)).inferenceClassification
    }

    private fun fetchEnvelope(messageServerId: String): MimeMessage {
        val url = client.url("me/messages/${pathSegment(messageServerId)}") {
            addQueryParameter("\$select", MESSAGE_ENVELOPE_SELECT)
            addQueryParameter("\$expand", MESSAGE_ENVELOPE_EXPAND)
        }

        val graphMessage = client.json.decodeFromString<GraphMessage>(client.getString(url))

        return graphMessage.toEnvelopeMessage()
    }
}
