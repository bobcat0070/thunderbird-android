package app.k9mail.autodiscovery.api

import net.thunderbird.core.common.net.Hostname

/**
 * Settings for a mailbox that is accessed through the Microsoft Graph API.
 *
 * Graph covers both retrieval and submission, so a single instance describes the incoming and the outgoing side of an
 * account. There is nothing for the user to configure beyond signing in: the endpoint is fixed and the only supported
 * authentication is OAuth 2.0.
 */
data class GraphServerSettings(
    val hostname: Hostname,
    val username: String,
) : IncomingServerSettings, OutgoingServerSettings
