package com.fsck.k9.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest
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
 * Namespaces this loader's entries in the shared cache.
 */
private const val CACHE_PREFIX = "gravatar:"

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
    private val cache: AvatarCache,
    private val logger: Logger,
    private val baseUrl: String = GRAVATAR_AVATAR_BASE_URL,
) {

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun loadGravatar(emailAddress: String, size: Int): Bitmap? {
        val settings = generalSettingsManager.getConfig().gravatar
        val address = emailAddress.trim().lowercase()
        if (!settings.isEnabled || address.isEmpty()) return null

        cache.get(CACHE_PREFIX + address)?.let { cached ->
            return if (cached.isEmpty()) null else decode(cached)
        }

        return try {
            fetch(address, size, settings.apiKey)
        } catch (e: Exception) {
            // Anything from a DNS failure to a truncated image. Not cached either way: an outage says
            // nothing about whether this address has a picture. The caller falls back to the letter avatar.
            logger.debug(TAG, e) { "Could not load Gravatar" }
            null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

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
                    cache.putMiss(CACHE_PREFIX + address)
                    null
                }

                !response.isSuccessful -> {
                    // Not remembered as a miss: a rate limit or an outage says nothing about this address.
                    // The URL is safe to log: it carries the hash, never the address.
                    logger.debug(TAG) { "Gravatar responded ${response.code} for $url" }
                    null
                }

                else -> {
                    val bytes = response.body.bytes()
                    cache.put(CACHE_PREFIX + address, bytes)
                    decode(bytes)
                }
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
