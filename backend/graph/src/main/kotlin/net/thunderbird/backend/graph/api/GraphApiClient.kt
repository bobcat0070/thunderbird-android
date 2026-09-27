package net.thunderbird.backend.graph.api

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import java.io.IOException
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
internal const val MAX_THROTTLE_RETRIES = 3
private const val DEFAULT_RETRY_AFTER_SECONDS = 5L
private const val MAX_RETRY_AFTER_SECONDS = 60L

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
internal const val HTTP_NOT_FOUND = 404
internal const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL_SERVER_ERROR = 500
internal const val HTTP_SERVICE_UNAVAILABLE = 503
internal const val HTTP_GATEWAY_TIMEOUT = 504

private const val MILLIS_PER_SECOND = 1000L
private const val MAX_ERROR_BODY_BYTES = 4096L
private const val MAX_HTTP_CODE = 599
private const val HTTP_LOWEST_CLIENT_ERROR = 400
private const val HTTP_HIGHEST_CLIENT_ERROR = 499

private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

/**
 * Where Graph hands out attachment upload URLs. They carry their own authorization, so they are checked against this
 * rather than being sent the account's token.
 */
private val UPLOAD_HOST_SUFFIXES = listOf("office.com", "office365.com", "outlook.com", "microsoft.com")

/**
 * Minimal HTTP client for the Microsoft Graph mail API.
 *
 * Responsibilities:
 * - attaching a bearer token obtained from [tokenProvider] and refreshing it once on 401
 * - honouring Graph throttling (429, 503, 504) via the Retry-After header
 * - translating API errors into [MessagingException] / [AuthenticationFailedException], and network failures into a
 *   temporary [MessagingException], which is what tells the app to keep a pending change and try it again later
 *
 * Instances hold no request state and are safe to use from the backend worker threads.
 */
// One method per shape of request Graph is sent; splitting them apart would scatter the authentication, throttling
// and error handling every one of them shares.
@Suppress("TooManyFunctions")
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
     *
     * The path is taken as already encoded, so any server ID in it must be passed through [pathSegment]: an ID
     * containing "/" would otherwise address a different resource from the one intended.
     */
    fun url(path: String, block: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        return baseHttpUrl.newBuilder()
            .addEncodedPathSegments(path)
            .apply(block)
            .build()
    }

    /**
     * Parses an absolute URL, as returned by Graph in @odata.nextLink and @odata.deltaLink.
     *
     * Refused unless it points at the same host as every other request, because the access token is attached
     * to whatever URL is requested. A link is stored between syncs, so this holds even for one that did not
     * arrive in the response being processed.
     */
    fun absoluteUrl(url: String): HttpUrl {
        val parsed = url.toHttpUrl()
        if (parsed.scheme != baseHttpUrl.scheme || parsed.host != baseHttpUrl.host || parsed.port != baseHttpUrl.port) {
            throw MessagingException("Refusing to follow a Microsoft Graph link to another host", true, null)
        }

        return parsed
    }

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

    /**
     * Parses an attachment upload URL returned by Graph.
     *
     * The URL is pre-authorized and lives on a different host from the API, so it is accepted only over https on a
     * Microsoft host - or on the API's own host, which is what a test server looks like.
     */
    fun uploadUrl(url: String): HttpUrl {
        val parsed = url.toHttpUrl()
        val isApiHost = parsed.scheme == baseHttpUrl.scheme && parsed.host == baseHttpUrl.host &&
            parsed.port == baseHttpUrl.port
        val isMicrosoftHost = parsed.isHttps && UPLOAD_HOST_SUFFIXES.any { suffix ->
            parsed.host == suffix || parsed.host.endsWith(".$suffix")
        }
        if (!isApiHost && !isMicrosoftHost) {
            throw MessagingException("Refusing a Microsoft Graph upload URL on an unexpected host", true, null)
        }

        return parsed
    }

    /**
     * Sends one piece of an attachment to an upload session.
     *
     * Deliberately without the account's token: the upload URL authorizes itself, and Graph documents that sending
     * the token along makes the upload fail.
     *
     * @param range the `Content-Range` value, e.g. `bytes 0-327679/1000000`.
     */
    fun putUploadChunk(uploadUrl: HttpUrl, chunk: ByteArray, range: String) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(chunk.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
            .header("Content-Range", range)
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw networkFailure(e)
        }

        response.use {
            if (!response.isSuccessful) {
                val isPermanent = response.code in HTTP_LOWEST_CLIENT_ERROR..HTTP_HIGHEST_CLIENT_ERROR &&
                    !response.isThrottled()
                throw MessagingException("Attachment upload failed (HTTP ${response.code})", isPermanent)
            }
        }
    }

    fun delete(url: HttpUrl) {
        execute(Request.Builder().url(url).delete().build()) { }
    }

    /**
     * Waits before retrying requests Graph throttled, as a batch does for the requests inside it.
     */
    fun pauseForThrottling(retryAfterSeconds: Long?, attempt: Int) {
        sleeper(retryDelayMillis(retryAfterSeconds, attempt))
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

            val response = try {
                okHttpClient.newCall(authorizedRequest).execute()
            } catch (e: IOException) {
                throw networkFailure(e)
            }

            response.use {
                when {
                    response.isSuccessful -> return try {
                        handler(response)
                    } catch (e: IOException) {
                        // The connection can also drop while the body is still arriving.
                        throw networkFailure(e)
                    }

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

private val SEGMENT_ENCODER = "https://graph.invalid/".toHttpUrl()

/**
 * Encodes one server ID as a single path segment for [GraphApiClient.url], so an ID containing "/" addresses
 * the resource it names rather than a different one.
 *
 * A dot segment is refused rather than encoded, because URL resolution removes it whichever form it is written
 * in. No Graph ID is "." or "..", so this can only be a response that is not worth following.
 */
internal fun pathSegment(value: String): String {
    if (value.trim() == "." || value.trim() == "..") {
        throw MessagingException("Refusing a Microsoft Graph identifier that is a path reference", true, null)
    }

    return SEGMENT_ENCODER.newBuilder().addPathSegment(value).build().encodedPathSegments.last()
}

private fun Response.isThrottled(): Boolean {
    return code == HTTP_TOO_MANY_REQUESTS || code == HTTP_SERVICE_UNAVAILABLE || code == HTTP_GATEWAY_TIMEOUT
}

private fun Response.retryAfterMillis(attempt: Int): Long {
    return retryDelayMillis(header("Retry-After")?.toLongOrNull(), attempt)
}

private fun retryDelayMillis(retryAfterSeconds: Long?, attempt: Int): Long {
    val seconds = retryAfterSeconds ?: (DEFAULT_RETRY_AFTER_SECONDS * attempt)
    return seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * MILLIS_PER_SECOND
}

/**
 * A failure to reach Graph at all, reported the way the IMAP backend reports one: temporary, so a pending change is
 * kept and tried again rather than dropped. The cause is kept for callers that tell network trouble apart.
 */
private fun networkFailure(cause: IOException): MessagingException {
    return MessagingException("Could not reach Microsoft Graph", cause)
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
