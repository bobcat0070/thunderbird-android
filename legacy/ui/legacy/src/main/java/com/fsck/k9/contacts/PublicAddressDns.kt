package com.fsck.k9.contacts

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * Resolves only to addresses on the public internet.
 *
 * The URLs sender pictures are fetched from are chosen by senders: a brand indicator's logo and certificate
 * locations come straight from the sender domain's DNS. Without this, any domain that passes its own DMARC
 * could point them at the reader's router, printer or anything else on the local network and have the app
 * request it every time the message list is drawn.
 *
 * Filtering at resolution rather than on the URL also covers a public name that resolves to a private address,
 * and a redirect to one, since OkHttp resolves every hop through here.
 */
class PublicAddressDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname).filter { it.isPublic() }
        if (addresses.isEmpty()) throw UnknownHostException("$hostname has no public address")

        return addresses
    }
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
