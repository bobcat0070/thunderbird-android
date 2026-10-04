package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.MESSAGE_ENVELOPE_EXPAND
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.backend.graph.api.receivedDate
import net.thunderbird.backend.graph.api.serverCategories
import net.thunderbird.backend.graph.api.setServerImportance
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
        val graphMessage = fetchEnvelope(messageServerId)
        val backendFolder = backendStorage.getFolder(folderServerId)

        // Stored under the id it was asked for by. Graph answers with the message's immutable id, which for a
        // folder whose stored ids have not been converted yet is a different one, and saving under that would
        // leave the message in the folder twice.
        val envelope = graphMessage.toEnvelopeMessage().apply { uid = messageServerId }

        backendFolder.saveMessage(envelope, MessageDownloadState.ENVELOPE)
        graphMessage.serverCategories()?.let { backendFolder.setMessageServerCategories(messageServerId, it) }
    }

    suspend fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        val message = fetchFullMessage(messageServerId)
        // The raw message says nothing about Focused Inbox, and the stored message is classified again from what
        // is saved here, so where it was sorted is asked for alongside - or opening it would change its category.
        // Its importance is asked for too, so that what is stored stays the mailbox's value rather than whatever
        // the raw headers happen to say.
        val serverState = fetchServerState(messageServerId)
        message.setServerRelevance(serverState.inferenceClassification)
        message.setServerImportance(serverState.importance)
        // The raw message carries no arrival time either, and saving it without one would move the message to
        // the moment it was opened.
        serverState.receivedDate()?.let { message.internalDate = it }

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

    private fun fetchServerState(messageServerId: String): GraphMessage {
        val url = client.url("me/messages/${pathSegment(messageServerId)}") {
            addQueryParameter("\$select", "inferenceClassification,importance,receivedDateTime")
        }

        return client.json.decodeFromString<GraphMessage>(client.getString(url))
    }

    private fun fetchEnvelope(messageServerId: String): GraphMessage {
        val url = client.url("me/messages/${pathSegment(messageServerId)}") {
            addQueryParameter("\$select", MESSAGE_ENVELOPE_SELECT)
            addQueryParameter("\$expand", MESSAGE_ENVELOPE_EXPAND)
        }

        return client.json.decodeFromString<GraphMessage>(client.getString(url))
    }
}
