package com.fsck.k9.ui.unsubscribe

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sends a one-click unsubscribe request (RFC 8058): an HTTPS POST of `List-Unsubscribe=One-Click` to the address the
 * sender put in its List-Unsubscribe header.
 *
 * The address is the sender's choice, so the client given here must be one that refuses local-network addresses,
 * and redirects are not followed - RFC 8058 has the sender answer the POST itself. Nothing is sent but the form
 * field: no cookies, no credentials, nothing about the reader.
 */
class OneClickUnsubscriber(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * @return whether the sender accepted the request.
     */
    suspend fun unsubscribe(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val url = uri.toString().toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@withContext false
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("List-Unsubscribe", "One-Click").build())
            .build()

        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}
