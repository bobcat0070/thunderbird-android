package net.thunderbird.backend.graph.api

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import java.io.InputStream
import kotlinx.serialization.json.Json
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0/"

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Media type used when creating or sending a message from raw RFC 5322 content.
 *
 * Graph expects the MIME content to be base64 encoded and the request to be declared as text/plain.
 */
private val MIME_MEDIA_TYPE = "text/plain".toMediaType()

private const val MAX_AUTH_RETRIES = 1
private const val MAX_THROTTLE_RETRIES = 3
private const val DEFAULT_RETRY_AFTER_SECONDS = 5L
private const val MAX_RETRY_AFTER_SECONDS = 60L

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504

private const val MILLIS_PER_SECOND = 1000L
private const val MAX_ERROR_BODY_BYTES = 4096L
private const val MAX_HTTP_CODE = 599

/**
 * Minimal HTTP client for the Microsoft Graph mail API.
 *
 * Responsibilities:
 * - attaching a bearer token obtained from [tokenProvider] and refreshing it once on 401
 * - honouring Graph throttling (429, 503, 504) via the Retry-After header
 * - translating API errors into [MessagingException] / [AuthenticationFailedException]
 *
 * Instances hold no request state and are safe to use from the backend worker threads.
 */
internal class GraphApiClient(
    private val okHttpClient: OkHttpClient,
    private val tokenProvider: OAuth2TokenProvider,
    baseUrl: String = GRAPH_BASE_URL,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val baseHttpUrl: HttpUrl = baseUrl.toHttpUrl()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Resolves a Graph path (e.g. "me/mailFolders") against the configured base URL.
     */
    fun url(path: String, block: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        return baseHttpUrl.newBuilder()
            .addPathSegments(path)
            .apply(block)
            .build()
    }

    /**
     * Parses an absolute URL, as returned by Graph in @odata.nextLink and @odata.deltaLink.
     */
    fun absoluteUrl(url: String): HttpUrl = url.toHttpUrl()

    /**
     * @param headers extra request headers, e.g. `Prefer: odata.maxpagesize` to control the page size of a
     *   collection response.
     */
    fun getString(url: HttpUrl, headers: Map<String, String> = emptyMap()): String {
        val requestBuilder = Request.Builder().url(url).get()
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }

        return execute(requestBuilder.build()) { response ->
            response.body.string()
        }
    }

    /**
     * Streams a response body, e.g. the raw MIME content of a message.
     *
     * The [block] is invoked with the body stream, which is closed when it returns.
     */
    fun <T> getStream(url: HttpUrl, block: (InputStream) -> T): T {
        return execute(Request.Builder().url(url).get().build()) { response ->
            response.body.byteStream().use(block)
        }
    }

    fun postJson(url: HttpUrl, body: String): String {
        return execute(Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()) { response ->
            response.body.string()
        }
    }

    /**
     * Posts base64 encoded RFC 5322 content, used to create or send a message from raw MIME.
     */
    fun postMime(url: HttpUrl, base64Mime: String): String {
        return execute(Request.Builder().url(url).post(base64Mime.toRequestBody(MIME_MEDIA_TYPE)).build()) { response ->
            response.body.string()
        }
    }

    fun patchJson(url: HttpUrl, body: String): String {
        return execute(Request.Builder().url(url).patch(body.toRequestBody(JSON_MEDIA_TYPE)).build()) { response ->
            response.body.string()
        }
    }

    fun delete(url: HttpUrl) {
        execute(Request.Builder().url(url).delete().build()) { }
    }

    /**
     * Executes [request] with authentication, token refresh and throttling retries applied.
     */
    @Suppress("ThrowsCount")
    private fun <T> execute(request: Request, handler: (Response) -> T): T {
        var authRetries = 0
        var throttleRetries = 0

        while (true) {
            val token = tokenProvider.getToken(OAuth2TokenProvider.OAUTH2_TIMEOUT.toLong())
            val authorizedRequest = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(authorizedRequest).execute()

            response.use {
                when {
                    response.isSuccessful -> return handler(response)

                    response.code == HTTP_UNAUTHORIZED && authRetries < MAX_AUTH_RETRIES -> {
                        authRetries++
                        tokenProvider.invalidateToken()
                    }

                    response.isThrottled() && throttleRetries < MAX_THROTTLE_RETRIES -> {
                        throttleRetries++
                        sleeper(response.retryAfterMillis(throttleRetries))
                    }

                    else -> throw response.toException(json)
                }
            }
        }
    }
}

private fun Response.isThrottled(): Boolean {
    return code == HTTP_TOO_MANY_REQUESTS || code == HTTP_SERVICE_UNAVAILABLE || code == HTTP_GATEWAY_TIMEOUT
}

private fun Response.retryAfterMillis(attempt: Int): Long {
    val headerValue = header("Retry-After")?.toLongOrNull()
    val seconds = headerValue ?: (DEFAULT_RETRY_AFTER_SECONDS * attempt)
    return seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * MILLIS_PER_SECOND
}

/**
 * Maps a Graph error response onto the exception types the backend contract expects.
 *
 * Only the error code is carried over. The message is not propagated or logged because Graph echoes request details,
 * which may contain recipient addresses or subjects.
 */
private fun Response.toException(json: Json): Exception {
    val errorCode = runCatching {
        json.decodeFromString<GraphError>(peekBody(MAX_ERROR_BODY_BYTES).string()).error?.code
    }.getOrNull()
    val description = errorCode ?: "HTTP $code"

    return when (code) {
        HTTP_UNAUTHORIZED -> AuthenticationFailedException(
            message = "Access token rejected by Microsoft Graph",
            messageFromServer = errorCode,
        )

        HTTP_FORBIDDEN -> AuthenticationFailedException(
            message = "Microsoft Graph denied access; the account may lack the required mail permissions",
            messageFromServer = errorCode,
        )

        HTTP_NOT_FOUND -> MessagingException("Microsoft Graph resource not found ($description)", true, null)

        in HTTP_INTERNAL_SERVER_ERROR..MAX_HTTP_CODE ->
            MessagingException("Microsoft Graph server error ($description)", false, null)

        else -> MessagingException("Microsoft Graph request failed ($description)", true, null)
    }
}
