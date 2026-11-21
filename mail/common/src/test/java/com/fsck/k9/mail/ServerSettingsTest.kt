package com.fsck.k9.mail

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasMessage
import assertk.assertions.isInstanceOf
import kotlin.test.Test

class ServerSettingsTest {
    @Test
    fun `creating typical ServerSettings should not throw`() {
        ServerSettings(
            type = "imap",
            host = "imap.domain.example",
            port = 993,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = "user",
            password = "123456",
            clientCertificateAlias = null,
        )
    }

    @Test
    fun `type that is not all lower case should throw`() {
        assertFailure {
            validServerSettings.copy(
                type = "IMAP"
            )
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("type must be all lower case")
    }

    @Test
    fun `username containing LF should throw`() {
        assertFailure {
            validServerSettings.copy(
                username = "user\nname"
            )
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("username must not contain line break")
    }

    @Test
    fun `username containing CR should throw`() {
        assertFailure {
            validServerSettings.copy(
                username = "user\rname"
            )
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("username must not contain line break")
    }

    @Test
    fun `password containing LF should throw`() {
        assertFailure {
            validServerSettings.copy(
                password = "123456\n"
            )
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("password must not contain line break")
    }

    @Test
    fun `password containing CR should throw`() {
        assertFailure {
            validServerSettings.copy(
                password = "123456\r"
            )
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("password must not contain line break")
    }

    @Test
    fun `sensitive information should not appear in toString`() {
        assertThat(validServerSettings.toString())
            .doesNotContain(validServerSettings.password!!)
    }

    @Test
    fun `toString should leave out the user name and extra values`() {
        val testSubject = validServerSettings.copy(
            authenticationType = AuthType.XOAUTH2,
            username = "user@domain.example",
            extra = mapOf("token" to "secret-extra"),
        )

        val result = testSubject.toString()

        assertThat(result).contains("imap.domain.example")
        assertThat(result).doesNotContain("secret-extra")
        assertThat(result).doesNotContain("user@domain.example")
    }

    private val validServerSettings get() = ServerSettings(
        type = "imap",
        host = "imap.domain.example",
        port = 993,
        connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
        authenticationType = AuthType.PLAIN,
        username = "user",
        password = "123456",
        clientCertificateAlias = null,
    )
}
