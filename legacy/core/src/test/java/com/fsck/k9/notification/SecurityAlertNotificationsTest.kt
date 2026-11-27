package com.fsck.k9.notification

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.testing.message.buildMessage
import com.fsck.k9.message.extractors.SecurityAlert
import kotlin.test.Test

class SecurityAlertNotificationsTest {
    @Test
    fun `a security alert that passed its sender check should be trusted`() {
        val message = message(subject = "New sign-in to your Lumen account")

        val alert = trustedSecurityAlert(message, isSenderAuthenticated = true)

        assertThat(alert).isEqualTo(SecurityAlert.SIGN_IN)
    }

    @Test
    fun `a security alert without a passed sender check should not be trusted`() {
        val message = message(subject = "New sign-in to your Lumen account")

        assertThat(trustedSecurityAlert(message, isSenderAuthenticated = false)).isNull()
    }

    @Test
    fun `a mailing list discussing passwords should not be a security alert`() {
        val message = message(subject = "Re: Reset your password page is broken", listId = "<dev.lists.example>")

        assertThat(trustedSecurityAlert(message, isSenderAuthenticated = true)).isNull()
    }

    @Test
    fun `ordinary mail should not be a security alert`() {
        val message = message(subject = "Your order has shipped")

        assertThat(trustedSecurityAlert(message, isSenderAuthenticated = true)).isNull()
    }

    private fun message(subject: String, listId: String? = null) = buildMessage {
        header("Subject", subject)
        if (listId != null) header("List-Id", listId)
        textBody()
    }
}
