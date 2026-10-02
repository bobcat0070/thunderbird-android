package net.thunderbird.backend.graph.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data transfer objects for the subset of the Microsoft Graph mail API used by this backend.
 *
 * Only the properties the backend requests via `$select` are modelled. Graph adds properties over time, so the JSON
 * parser is configured to ignore unknown keys.
 *
 * See https://learn.microsoft.com/en-us/graph/api/resources/mail-api-overview
 */
@Serializable
internal data class GraphCollection<T>(
    val value: List<T> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null,
)

@Serializable
internal data class GraphMailFolder(
    val id: String,
    val displayName: String? = null,
    val parentFolderId: String? = null,
    val childFolderCount: Int = 0,
    val totalItemCount: Int = 0,
    val unreadItemCount: Int = 0,
)

@Serializable
internal data class GraphMessage(
    val id: String,
    /**
     * The folder the message is in, which a search across the whole mailbox needs to say where each match is.
     */
    val parentFolderId: String? = null,
    val isRead: Boolean? = null,
    val isDraft: Boolean? = null,
    val receivedDateTime: String? = null,
    val sentDateTime: String? = null,
    val internetMessageId: String? = null,
    val subject: String? = null,
    val from: GraphRecipient? = null,
    val sender: GraphRecipient? = null,
    val replyTo: List<GraphRecipient> = emptyList(),
    val toRecipients: List<GraphRecipient> = emptyList(),
    val ccRecipients: List<GraphRecipient> = emptyList(),
    val bccRecipients: List<GraphRecipient> = emptyList(),
    val hasAttachments: Boolean? = null,

    /**
     * Short plain-text extract of the message body that Graph returns with the envelope. Used for the message list
     * preview so unread mail shows the first lines without downloading the whole message.
     */
    val bodyPreview: String? = null,

    /**
     * RFC 5322 headers. Graph only returns these when they are explicitly selected.
     */
    val internetMessageHeaders: List<GraphInternetMessageHeader> = emptyList(),
    val flag: GraphFollowupFlag? = null,

    /**
     * MAPI properties Graph has no field for, when asked for with `$expand` - here, what was last done with the
     * message, which is how Outlook knows it was replied to or forwarded.
     */
    val singleValueExtendedProperties: List<GraphExtendedProperty> = emptyList(),

    /**
     * Where Focused Inbox sorted the message: `focused` or `other`.
     */
    val inferenceClassification: String? = null,

    /**
     * How important the sender marked the message: `low`, `normal` or `high`.
     */
    val importance: String? = null,

    /**
     * The names of the Outlook categories assigned to the message, or `null` when the response did not include
     * them - which says nothing about the message and must not be read as "none".
     */
    val categories: List<String>? = null,

    /**
     * Present only in delta responses, marking a message that is no longer in the folder.
     */
    @SerialName("@removed") val removed: GraphRemoved? = null,
)

/**
 * Delta annotation describing why a message left the folder. `deleted` means it was removed or moved elsewhere.
 */
@Serializable
internal data class GraphRemoved(
    val reason: String? = null,
)

@Serializable
internal data class GraphExtendedProperty(
    val id: String? = null,
    val value: String? = null,
)

@Serializable
internal data class GraphInternetMessageHeader(
    val name: String? = null,
    val value: String? = null,
)

@Serializable
internal data class GraphRecipient(
    val emailAddress: GraphEmailAddress? = null,
)

@Serializable
internal data class GraphEmailAddress(
    val name: String? = null,
    val address: String? = null,
)

@Serializable
internal data class GraphFollowupFlag(
    val flagStatus: String? = null,
)

@Serializable
internal data class GraphError(
    val error: GraphErrorDetail? = null,
)

@Serializable
internal data class GraphErrorDetail(
    val code: String? = null,
    val message: String? = null,
)

/**
 * Value of [GraphFollowupFlag.flagStatus] that corresponds to a flagged message.
 */
internal const val FLAG_STATUS_FLAGGED = "flagged"
