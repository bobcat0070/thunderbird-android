package net.thunderbird.backend.graph

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import net.thunderbird.backend.graph.api.GRAPH_BASE_URL
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.command.GraphContactStore
import net.thunderbird.core.logging.Logger
import okhttp3.OkHttpClient

/**
 * Creates a [GraphBackend].
 *
 * @param tokenProvider supplies the OAuth 2.0 access token. Microsoft 365 does not offer any other way to
 *   authenticate against Graph, so a token provider is mandatory.
 * @param pushSupport what the app provides so the account can poll frequently from the push foreground service.
 *   Omit it and the account reports itself as not push capable and relies on periodic background sync.
 * @param contactStore where the mailbox's own contacts are kept for address completion. Omit it and contacts are
 *   never fetched, which is what a backend with nothing to complete into wants.
 * @param baseUrl overridable for tests and for the sovereign Graph endpoints that some tenants use.
 */
fun createGraphBackend(
    backendStorage: BackendStorage,
    okHttpClient: OkHttpClient,
    tokenProvider: OAuth2TokenProvider,
    logger: Logger,
    pushSupport: GraphPushSupport? = null,
    contactStore: GraphContactStore? = null,
    baseUrl: String = GRAPH_BASE_URL,
): GraphBackend {
    return GraphBackend(
        backendStorage = backendStorage,
        client = GraphApiClient(
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider,
            baseUrl = baseUrl,
        ),
        logger = logger,
        pushSupport = pushSupport,
        contactStore = contactStore,
    )
}
