package com.fsck.k9.contacts.bimi

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val DNS_CLASS_IN = 1
private const val LOOKUP_TIMEOUT_SECONDS = 5L

/**
 * Looks up TXT records using the platform resolver.
 *
 * The platform resolver is used rather than DNS-over-HTTPS so the lookup goes to whatever resolver the device
 * is already configured to trust. Sending sender domains to a third-party resolver instead would tell that
 * resolver who this user gets mail from, which is a worse trade than the feature is worth.
 *
 * Requires API 29; there is no public API for raw DNS queries before that, so BIMI is simply unavailable on
 * older devices rather than being approximated by something less trustworthy.
 */
interface DnsTxtLookup {
    fun txtRecords(name: String): List<String>
}

class PlatformDnsTxtLookup(private val executor: Executor) : DnsTxtLookup {

    @Suppress("ReturnCount")
    override fun txtRecords(name: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val latch = CountDownLatch(1)
        var response: ByteArray? = null

        DnsResolver.getInstance().rawQuery(
            null,
            name,
            DNS_CLASS_IN,
            DNS_TYPE_TXT,
            DnsResolver.FLAG_EMPTY,
            executor,
            CancellationSignal(),
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) response = answer
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    // A domain with no BIMI record is the common case and arrives here as NXDOMAIN.
                    latch.countDown()
                }
            },
        )

        // Bounded: the caller is decoding a list row, and a resolver that never answers must not hold it.
        if (!latch.await(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return emptyList()

        return response?.let { parseTxtRecords(it) }.orEmpty()
    }
}
