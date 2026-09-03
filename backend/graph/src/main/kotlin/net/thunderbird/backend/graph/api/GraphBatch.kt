package net.thunderbird.backend.graph.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Maximum number of requests Microsoft Graph accepts in a single `$batch` payload.
 *
 * See https://learn.microsoft.com/en-us/graph/json-batching
 */
internal const val GRAPH_BATCH_LIMIT = 20

private const val HTTP_LOWEST_SUCCESS_CODE = 200
private const val HTTP_HIGHEST_SUCCESS_CODE = 299

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
) {
    val isSuccess: Boolean get() = status in HTTP_LOWEST_SUCCESS_CODE..HTTP_HIGHEST_SUCCESS_CODE
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
 * given.
 *
 * @return the response for each request, keyed by its index in [items].
 */
internal fun GraphApiClient.batchExecute(items: List<GraphBatchRequestItem>): Map<Int, GraphBatchResponseItem> {
    if (items.isEmpty()) return emptyMap()

    val batchUrl = url("\$batch")

    return buildMap {
        items.chunked(GRAPH_BATCH_LIMIT).forEach { chunk ->
            val responseBody = postJson(batchUrl, json.encodeToString(GraphBatchRequest(chunk)))
            val batchResponse = json.decodeFromString<GraphBatchResponse>(responseBody)

            for (item in batchResponse.responses) {
                val index = item.id.toIntOrNull() ?: continue

                put(index, item)
            }
        }
    }
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
