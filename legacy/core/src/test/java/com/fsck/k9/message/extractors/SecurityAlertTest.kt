package com.fsck.k9.message.extractors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

class SecurityAlertTest {
    @Test
    fun `sign-in alerts should be recognised`() {
        assertThat(findSecurityAlert("New sign-in to your Lumen account")).isEqualTo(SecurityAlert.SIGN_IN)
        assertThat(findSecurityAlert("Unusual sign-in activity")).isEqualTo(SecurityAlert.SIGN_IN)
        assertThat(findSecurityAlert("Someone signed in from a new device")).isEqualTo(SecurityAlert.SIGN_IN)
        assertThat(findSecurityAlert("Login attempt from Lisbon")).isEqualTo(SecurityAlert.SIGN_IN)
        assertThat(findSecurityAlert("Neue Anmeldung bei Harbor")).isEqualTo(SecurityAlert.SIGN_IN)
    }

    @Test
    fun `a password reset request should be told from a changed password`() {
        assertThat(findSecurityAlert("Reset your password")).isEqualTo(SecurityAlert.PASSWORD_RESET)
        assertThat(findSecurityAlert("Password reset request")).isEqualTo(SecurityAlert.PASSWORD_RESET)
        assertThat(findSecurityAlert("Forgot your password?")).isEqualTo(SecurityAlert.PASSWORD_RESET)
        assertThat(findSecurityAlert("Your password was reset")).isEqualTo(SecurityAlert.PASSWORD_CHANGED)
        assertThat(findSecurityAlert("Your password has been changed")).isEqualTo(SecurityAlert.PASSWORD_CHANGED)
        assertThat(findSecurityAlert("Password reset successful")).isEqualTo(SecurityAlert.PASSWORD_CHANGED)
        assertThat(findSecurityAlert("Ihr Passwort wurde geändert")).isEqualTo(SecurityAlert.PASSWORD_CHANGED)
    }

    @Test
    fun `changes to how the account is protected should be recognised`() {
        assertThat(findSecurityAlert("2-Step Verification turned off")).isEqualTo(SecurityAlert.SECURITY_SETTINGS)
        assertThat(findSecurityAlert("Two-factor authentication was disabled"))
            .isEqualTo(SecurityAlert.SECURITY_SETTINGS)
        assertThat(findSecurityAlert("Your recovery email was changed")).isEqualTo(SecurityAlert.SECURITY_SETTINGS)
        assertThat(findSecurityAlert("A passkey was added to your account")).isEqualTo(SecurityAlert.SECURITY_SETTINGS)
    }

    @Test
    fun `a security alert that says no more should still count`() {
        assertThat(findSecurityAlert("Security alert")).isEqualTo(SecurityAlert.OTHER)
        assertThat(findSecurityAlert("Critical security alert for your account")).isEqualTo(SecurityAlert.OTHER)
    }

    @Test
    fun `ordinary mail should not be taken for a security alert`() {
        assertThat(findSecurityAlert("Your order has shipped")).isNull()
        assertThat(findSecurityAlert("Sign in to Medium")).isNull()
        assertThat(findSecurityAlert("Your verification code is 482913")).isNull()
        assertThat(findSecurityAlert("Turn on two-factor authentication today")).isNull()
        assertThat(findSecurityAlert("Tips for a stronger password")).isNull()
        assertThat(findSecurityAlert("Your new device is ready")).isNull()
        assertThat(findSecurityAlert("RE: Fwd: New sign-in to your account")).isNull()
        assertThat(findSecurityAlert("Fw: Microsoft account security alert")).isNull()
        assertThat(findSecurityAlert("AW: Passwort zurücksetzen")).isNull()
        assertThat(findSecurityAlert("Security Alert: Your one-time sign in code is 534635.")).isNull()
        assertThat(findSecurityAlert(null)).isNull()
        assertThat(findSecurityAlert("")).isNull()
    }

    @Test
    fun `a breach named in the subject should be a breach notice`() {
        assertThat(isBreachNotice("Notice of Data Breach", null, isNewsletter = false)).isTrue()
        assertThat(isBreachNotice("Important information about a security incident", null, isNewsletter = false))
            .isTrue()
        assertThat(isBreachNotice("You've been pwned!", null, isNewsletter = false)).isTrue()
        assertThat(isBreachNotice("Datenleck bei Harbor Books", null, isNewsletter = false)).isTrue()
    }

    @Test
    fun `a notice that leaves the breach to its text should be a breach notice`() {
        val text = "We are writing to let you know that your personal information may have been accessed by an " +
            "unauthorized third party."

        assertThat(isBreachNotice("An important notice from Harbor Books", text, isNewsletter = false)).isTrue()
    }

    @Test
    fun `a newsletter writing about breaches should not be a breach notice`() {
        val text = "This week: a data breach at a large retailer, and how to protect yourself."

        assertThat(isBreachNotice("Important: this week in security", text, isNewsletter = true)).isFalse()
    }

    @Test
    fun `a breach in the text of mail whose subject is not a notice should be left alone`() {
        val text = "Our products help companies recover from a data breach."

        assertThat(isBreachNotice("Your invoice for November", text, isNewsletter = false)).isFalse()
        assertThat(isBreachNotice("Your order has shipped", null, isNewsletter = false)).isFalse()
        assertThat(isBreachNotice("RE: Fwd: Notice of Recent Security Incident", null, isNewsletter = false)).isFalse()
    }
}
