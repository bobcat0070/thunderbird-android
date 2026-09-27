package com.fsck.k9.contacts

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Resolves only to addresses on the public internet.
 *
 * The URLs sender pictures are fetched from are chosen by senders: a brand indicator's logo and certificate
 * locations come straight from the sender domain's DNS. Without this, any domain that passes its own DMARC
 * could point them at the reader's router, printer or anything else on the local network and have the app
 * request it every time the message list is drawn.
 *
 * Filtering at resolution rather than on the URL also covers a public name that resolves to a private address,
 * and a redirect to one, since OkHttp resolves every hop through here - every hop that names a host, that is.
 * A URL naming an IP address is connected to without asking DNS at all, which is what [PublicAddressInterceptor]
 * is for.
 */
class PublicAddressDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname).filter { it.isPublic() }
        if (addresses.isEmpty()) throw UnknownHostException("$hostname has no public address")

        return addresses
    }
}

/**
 * Refuses to talk to anything but the public internet, judged by the address a connection actually reached.
 *
 * The other half of [PublicAddressDns]: OkHttp connects to a URL naming an IP address, like
 * `https://192.168.1.1/`, without a DNS lookup, so a BIMI record or a redirect naming one would get past it. A
 * network interceptor sees every hop, redirects included, once its connection is made and before anything is
 * sent on it, so a request to the local network is never made.
 *
 * Through a proxy the connection is to the proxy, which does the reaching; that is the user's arrangement and is
 * left to it.
 */
class PublicAddressInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val route = chain.connection()?.route()
        val isDirect = route?.proxy?.type() == Proxy.Type.DIRECT
        val address = route?.socketAddress?.address

        if (isDirect && address != null && !address.isPublic()) {
            throw UnknownHostException("${chain.request().url.host} is not a public address")
        }

        return chain.proceed(chain.request())
    }
}

/**
 * The client every sender-chosen URL is fetched through: brand logos, their certificates, and the picture
 * services.
 *
 * @param timeoutSeconds how long a connection or read may take before the picture is given up on.
 */
fun senderPictureHttpClient(timeoutSeconds: Long): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        // Neither reach the local network, by name or by address, nor let an https URL be redirected to plain
        // http, which would undo the https-only check on BIMI records.
        .dns(PublicAddressDns())
        .addNetworkInterceptor(PublicAddressInterceptor())
        .followSslRedirects(false)
        .build()
}

private const val BYTE_MASK = 0xFF

@Suppress("MagicNumber", "ReturnCount")
internal fun InetAddress.isPublic(): Boolean {
    val isLocal = listOf(isAnyLocalAddress, isLoopbackAddress, isLinkLocalAddress, isSiteLocalAddress)
    if (isLocal.any { it } || isMulticastAddress) return false

    val bytes = address.map { it.toInt() and BYTE_MASK }

    return when (this) {
        is Inet4Address -> when {
            bytes[0] == 0 -> false
            // Carrier-grade NAT, which is a private network as far as the reader is concerned.
            bytes[0] == 100 && bytes[1] in 64..127 -> false
            // Reserved and broadcast.
            bytes[0] >= 240 -> false
            else -> true
        }

        // Unique local addresses, fc00::/7 - the IPv6 counterpart of the private IPv4 ranges.
        is Inet6Address -> bytes[0] and 0xFE != 0xFC

        else -> false
    }
}
