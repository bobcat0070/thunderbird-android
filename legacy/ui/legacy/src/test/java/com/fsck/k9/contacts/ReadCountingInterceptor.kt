package com.fsck.k9.contacts

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * Counts how much of each response body is read, to tell a fetch that stops at its limit from one that reads the
 * whole body and only then finds it too large.
 */
class ReadCountingInterceptor : Interceptor {
    var bytesRead = 0L
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                return super.read(sink, byteCount).also { count -> if (count > 0) bytesRead += count }
            }
        }

        return response.newBuilder()
            .body(counted.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }
}
