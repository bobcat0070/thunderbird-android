package com.fsck.k9.contacts

import net.thunderbird.core.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request

private const val CACHE_PREFIX = "url:"
private const val TAG = "CachingUrlFetcher"

/**
 * Caps what a single fetch may pull in, so a hostile or broken host cannot stream without end.
 */
private const val MAX_BYTES = 2 * 1024 * 1024

/**
 * Fetches a URL, reusing what was fetched last time.
 *
 * Used for revocation lists, which are shared by every certificate an authority issues and change slowly: a
 * list downloaded once serves every mark from that authority until it expires, which is the difference
 * between checking revocation being affordable and not.
 */
class CachingUrlFetcher(
    private val httpClient: OkHttpClient,
    private val cache: AvatarCache,
    private val logger: Logger? = null,
) {
    @Suppress("TooGenericExceptionCaught")
    fun fetch(url: String): ByteArray? {
        cache.get(CACHE_PREFIX + url)?.let { cached ->
            return cached.takeIf { it.isNotEmpty() }
        }

        return try {
            download(url)?.also { cache.put(CACHE_PREFIX + url, it) }
        } catch (e: Exception) {
            // Not cached as a miss: an outage says nothing about what lives at this URL.
            logger?.debug(TAG, e) { "Could not fetch $url" }
            null
        }
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body.contentLength() > MAX_BYTES) {
                null
            } else {
                response.body.bytes().takeIf { it.size <= MAX_BYTES }
            }
        }
    }
}
