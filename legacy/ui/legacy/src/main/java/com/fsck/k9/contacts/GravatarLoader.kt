package com.fsck.k9.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest
import java.util.Collections
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.core.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The host WordPress itself embeds avatars from, and the one to use from an app.
 *
 * `gravatar.com` refuses this client outright with a bare nginx 403 — not a bad request, since the identical
 * URL succeeds from a browser on the same device and from curl on the same network. `secure.gravatar.com`
 * serves it. `d=404` is what makes a miss a miss rather than a generated placeholder, which the app draws
 * itself and rather better.
 */
const val GRAVATAR_AVATAR_BASE_URL = "https://secure.gravatar.com/avatar/"

/**
 * How many addresses to remember as having no Gravatar.
 *
 * Misses are the common case — most senders in a mailbox have never heard of Gravatar — and without this
 * every scroll past the same message would ask again.
 */
private const val MISS_CACHE_SIZE = 512

private const val TAG = "GravatarLoader"

/**
 * Gravatar answers a request for an address it does not know with this, because of `d=404`.
 */
private const val HTTP_NOT_FOUND = 404

/**
 * Identifies the app rather than the HTTP library, which is what a well-behaved client does.
 */
private const val USER_AGENT = "Thunderbird-Android"

/**
 * The endpoint returns an image; OkHttp sends no Accept header of its own.
 */
private const val ACCEPT = "image/*"

/**
 * Fetches sender pictures from Gravatar.
 *
 * Only ever called after the device's own contacts have been asked, so a picture the user has for a person
 * always wins over one the internet has.
 */
class GravatarLoader(
    private val generalSettingsManager: GeneralSettingsManager,
    private val httpClient: OkHttpClient,
    private val logger: Logger,
    private val baseUrl: String = GRAVATAR_AVATAR_BASE_URL,
) {

    /**
     * Addresses known to have no Gravatar, oldest evicted first.
     *
     * Held for the life of the process rather than given an expiry: being stale here costs a picture that
     * appears one restart late, while the alternative is re-asking for hundreds of addresses that will go on
     * saying no.
     */
    private val addressesWithoutGravatar: MutableMap<String, Unit> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Unit>?): Boolean = size > MISS_CACHE_SIZE
        },
    )

    @Suppress("TooGenericExceptionCaught")
    fun loadGravatar(emailAddress: String, size: Int): Bitmap? {
        val settings = generalSettingsManager.getConfig().gravatar
        val address = emailAddress.trim().lowercase()
        val shouldLookUp = settings.isEnabled &&
            address.isNotEmpty() &&
            !addressesWithoutGravatar.containsKey(address)

        if (!shouldLookUp) return null

        return try {
            fetch(address, size, settings.apiKey)
        } catch (e: Exception) {
            // Anything from a DNS failure to a truncated image. A failed lookup is not worth failing the row
            // over: the caller falls back to the letter avatar.
            logger.debug(TAG, e) { "Could not load Gravatar" }
            null
        }
    }

    private fun fetch(address: String, size: Int, apiKey: String): Bitmap? {
        val url = "$baseUrl${address.sha256()}?s=$size&d=404"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .apply {
                // Optional: the avatar endpoint serves anonymous requests too, and a key only raises the
                // rate limit.
                if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
            }
            .build()

        return httpClient.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> {
                    addressesWithoutGravatar[address] = Unit
                    null
                }

                !response.isSuccessful -> {
                    // Not remembered as a miss: a rate limit or an outage says nothing about this address.
                    // The URL is safe to log: it carries the hash, never the address.
                    logger.debug(TAG) { "Gravatar responded ${response.code} for $url" }
                    null
                }

                else -> response.body.byteStream().use { BitmapFactory.decodeStream(it) }
            }
        }
    }
}

/**
 * Gravatar identifies an address by the hash rather than the address itself, so the lookup does not send the
 * address in the clear. SHA-256 is the current scheme; the older MD5 form still resolves but is not used here.
 */
private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))

    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
