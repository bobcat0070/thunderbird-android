package net.thunderbird.backend.graph.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.thunderbird.core.common.exception.MessagingException

/**
 * Maximum number of requests Microsoft Graph accepts in a single `$batch` payload.
 *
 * See https://learn.microsoft.com/en-us/graph/json-batching
 */
internal const val GRAPH_BATCH_LIMIT = 20

private const val HTTP_LOWEST_SUCCESS_CODE = 200
private const val HTTP_HIGHEST_SUCCESS_CODE = 299
private const val HTTP_LOWEST_CLIENT_ERROR_CODE = 400
private const val HTTP_HIGHEST_CLIENT_ERROR_CODE = 499

@Serializable
internal data class GraphBatchRequest(
    val requests: List<GraphBatchRequestItem>,
)

@Serializable
internal data class GraphBatchRequestItem(
    val id: String,
    val method: String,
    val url: String,
    val body: JsonObject? = null,
    /**
     * Graph requires the content type to be declared per request whenever a request carries a body.
     */
    val headers: Map<String, String>? = null,
)

@Serializable
internal data class GraphBatchResponse(
    val responses: List<GraphBatchResponseItem> = emptyList(),
)

@Serializable
internal data class GraphBatchResponseItem(
    val id: String,
    val status: Int,
    val body: JsonObject? = null,
    val headers: Map<String, String>? = null,
) {
    val isSuccess: Boolean get() = status in HTTP_LOWEST_SUCCESS_CODE..HTTP_HIGHEST_SUCCESS_CODE

    /**
     * Graph limits how many requests it works on at once for one mailbox and turns the rest of a batch away, so a
     * throttled item is routine rather than a failure.
     */
    val isThrottled: Boolean
        get() = status == HTTP_TOO_MANY_REQUESTS || status == HTTP_SERVICE_UNAVAILABLE || status == HTTP_GATEWAY_TIMEOUT

    val retryAfterSeconds: Long?
        get() = headers?.entries?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.toLongOrNull()
}

/**
 * Builds a batch item, attaching the content type Graph requires alongside a body.
 */
internal fun graphBatchItem(
    index: Int,
    method: String,
    url: String,
    body: JsonObject? = null,
): GraphBatchRequestItem {
    return GraphBatchRequestItem(
        id = index.toString(),
        method = method,
        url = url,
        body = body,
        headers = body?.let { mapOf("Content-Type" to "application/json") },
    )
}

/**
 * Sends [items] as `$batch` requests, chunked to respect [GRAPH_BATCH_LIMIT].
 *
 * Batching matters beyond saving round trips: Graph throttles per request, so an operation spanning many messages is
 * far likelier to complete when sent as a handful of batches rather than hundreds of individual calls.
 *
 * An individual request can fail without failing the batch, so every outcome is reported by the index its item was
 * given. Requests Graph throttled are sent again, after the wait it asked for, before their outcome is reported.
 *
 * @return the response for each request, keyed by its index in [items].
 */
internal fun GraphApiClient.batchExecute(items: List<GraphBatchRequestItem>): Map<Int, GraphBatchResponseItem> {
    if (items.isEmpty()) return emptyMap()

    val batchUrl = url("\$batch")

    return buildMap {
        items.chunked(GRAPH_BATCH_LIMIT).forEach { chunk ->
            var pending = chunk
            var attempt = 0

            while (pending.isNotEmpty()) {
                val responseBody = postJson(batchUrl, json.encodeToString(GraphBatchRequest(pending)))
                val responses = json.decodeFromString<GraphBatchResponse>(responseBody).responses

                for (item in responses) {
                    val index = item.id.toIntOrNull() ?: continue

                    put(index, item)
                }

                val throttled = responses.filter { it.isThrottled }
                if (throttled.isEmpty() || attempt >= MAX_THROTTLE_RETRIES) break

                attempt++
                pauseForThrottling(throttled.mapNotNull { it.retryAfterSeconds }.maxOrNull(), attempt)

                val throttledIds = throttled.mapTo(HashSet()) { it.id }
                pending = pending.filter { it.id in throttledIds }
            }
        }
    }
}

/**
 * Fails when any request in a batch did, so the pending command that issued it is kept and tried again instead of
 * being treated as done.
 *
 * A message that no longer exists is not a failure: whatever was asked of it no longer matters, just as the IMAP
 * backend does not complain about a message that has already gone. A client error is reported as permanent, since
 * sending the same request again would only be refused again.
 *
 * @param operation what was being done, for the message, e.g. "update".
 */
internal fun Map<Int, GraphBatchResponseItem>.requireSuccess(operation: String) {
    val failures = values.filter { !it.isSuccess && it.status != HTTP_NOT_FOUND }
    if (failures.isEmpty()) return

    val isPermanent = failures.all {
        it.status in HTTP_LOWEST_CLIENT_ERROR_CODE..HTTP_HIGHEST_CLIENT_ERROR_CODE && !it.isThrottled
    }

    throw MessagingException(
        "Microsoft Graph could not $operation ${failures.size} message(s) (HTTP ${failures.first().status})",
        isPermanent,
    )
}

/**
 * Issues [relativeUrls] as `GET` requests inside one or more `$batch` calls.
 *
 * @param relativeUrls Graph-relative URLs, e.g. `/me/mailFolders/inbox`.
 * @return the response bodies of the successful requests, keyed by their index in [relativeUrls].
 */
internal fun GraphApiClient.batchGet(relativeUrls: List<String>): Map<Int, JsonObject> {
    val responses = batchExecute(
        relativeUrls.mapIndexed { index, relativeUrl -> graphBatchItem(index, "GET", relativeUrl) },
    )

    return buildMap {
        for ((index, response) in responses) {
            val body = response.body
            if (response.isSuccess && body != null) {
                put(index, body)
            }
        }
    }
}
