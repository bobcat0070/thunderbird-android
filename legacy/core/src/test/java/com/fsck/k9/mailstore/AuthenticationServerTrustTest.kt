package com.fsck.k9.mailstore

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.Preferences
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import net.thunderbird.core.common.mail.Protocols
import org.junit.Before
import org.junit.Test
import org.koin.core.component.inject

class AuthenticationServerTrustTest : K9RobolectricTest() {
    private val preferences: Preferences by inject()
    private val testSubject by lazy { DefaultAuthenticationServerTrust(preferences, preferences) }

    @Before
    fun before() {
        preferences.clearAccounts()
    }

    @Test
    fun `a server that stamps every message should be learned`() {
        val account = accountOn(Protocols.IMAP, "imap.example.net")

        repeat(20) { testSubject.observe(account, listOf("mx.example.net; dmarc=pass header.from=a.example")) }

        assertThat(testSubject.trustedServerId(account)).isEqualTo("mx.example.net")
    }

    @Test
    fun `nothing should be learned from a server that stamps nothing`() {
        // What a sender-written header looks like on such a server: now and then, and under all sorts of names.
        val account = accountOn(Protocols.IMAP, "imap.example.net")

        repeat(18) { testSubject.observe(account, emptyList()) }
        testSubject.observe(account, listOf("forged.example; dmarc=pass header.from=bank.example"))
        testSubject.observe(account, listOf("forged.example; dmarc=pass header.from=bank.example"))

        assertThat(testSubject.trustedServerId(account)).isNull()
    }

    @Test
    fun `a name should not be learned from too few messages`() {
        val account = accountOn(Protocols.IMAP, "imap.example.net")

        repeat(5) { testSubject.observe(account, listOf("mx.example.net; dmarc=pass header.from=a.example")) }

        assertThat(testSubject.trustedServerId(account)).isNull()
    }

    @Test
    fun `Microsoft 365 and Gmail should be trusted from the first message`() {
        assertThat(testSubject.trustedServerId(accountOn(Protocols.GRAPH, "graph.microsoft.com")))
            .isEqualTo(UNNAMED_AUTHENTICATION_SERVER)
        assertThat(testSubject.trustedServerId(accountOn(Protocols.IMAP, "outlook.office365.com")))
            .isEqualTo(UNNAMED_AUTHENTICATION_SERVER)
        assertThat(testSubject.trustedServerId(accountOn(Protocols.IMAP, "imap.gmail.com")))
            .isEqualTo("mx.google.com")
    }

    private fun accountOn(protocol: String, host: String): String {
        val account = preferences.newAccount()
        account.incomingServerSettings = ServerSettings(
            protocol,
            host,
            993,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "username",
            "password",
            null,
        )
        account.outgoingServerSettings = ServerSettings(
            Protocols.SMTP,
            host,
            465,
            ConnectionSecurity.SSL_TLS_REQUIRED,
            AuthType.PLAIN,
            "username",
            "password",
            null,
        )
        preferences.saveAccount(account)

        return account.id.toString()
    }
}
