package com.fsck.k9.contacts

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Test

class PublicAddressDnsTest {

    @Test
    fun `public addresses should be allowed`() {
        assertThat(address("93.184.216.34").isPublic()).isTrue()
        assertThat(address("2606:2800:220:1:248:1893:25c8:1946").isPublic()).isTrue()
    }

    @Test
    fun `local network addresses should be refused`() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "172.16.5.4",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "255.255.255.255",
            "::1",
            "fe80::1",
            "fd12:3456:789a::1",
        ).forEach { literal ->
            assertThat(address(literal).isPublic(), literal).isFalse()
        }
    }

    @Test
    fun `lookup should drop private addresses and keep public ones`() {
        val dns = PublicAddressDns(fakeDns("192.168.1.1", "93.184.216.34"))

        assertThat(dns.lookup("mixed.example")).containsExactly(address("93.184.216.34"))
    }

    @Test
    fun `lookup should fail when only private addresses are found`() {
        val dns = PublicAddressDns(fakeDns("10.0.0.1"))

        assertFailure { dns.lookup("router.example") }.isInstanceOf<UnknownHostException>()
    }

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun fakeDns(vararg literals: String) = Dns { literals.map { address(it) } }
}
