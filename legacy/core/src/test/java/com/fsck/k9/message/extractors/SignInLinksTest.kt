package com.fsck.k9.message.extractors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.testing.message.buildMessage
import kotlin.test.Test

class SignInLinksTest {
    @Test
    fun `a button saying sign in should be the link in a sign-in mail`() {
        val testSubject = htmlMessage(
            """
            <p>Hi, click below to finish signing in.</p>
            <a href="https://medium.example/m/callback/email?token=abc">Sign in to Medium</a>
            <p><a href="https://medium.example/settings">Manage your account</a></p>
            """.trimIndent(),
        )

        val link = findSignInLink(testSubject, subject = "Sign in to Medium")

        assertThat(link).isEqualTo("https://medium.example/m/callback/email?token=abc")
    }

    @Test
    fun `verify and magic link buttons should count`() {
        assertThat(
            findSignInLinkInHtml("""<a href="https://slack.example/confirm/1">Confirm Email Address</a>"""),
        ).isEqualTo("https://slack.example/confirm/1")
        assertThat(
            findSignInLinkInHtml(
                """<a href="https://notion.example/login/2">Click here to log in with this magic link</a>""",
            ),
        ).isEqualTo("https://notion.example/login/2")
        assertThat(
            findSignInLinkInHtml("""<a href="https://acme.example/approve/3"><b>Yes, it's me</b></a>"""),
        ).isEqualTo("https://acme.example/approve/3")
        assertThat(
            findSignInLinkInHtml("""<a href="https://shop.example/de/4">Jetzt anmelden</a>"""),
        ).isEqualTo("https://shop.example/de/4")
    }

    @Test
    fun `a mail whose subject is not about signing in should not be read`() {
        val testSubject = htmlMessage("""<a href="https://shop.example/account">Sign in</a>""")

        val link = findSignInLink(testSubject, subject = "This week's deals")

        assertThat(link).isNull()
    }

    @Test
    fun `links that only mention signing in should be left alone`() {
        val html = """
            <a href="https://shop.example/help">Having trouble signing in? Visit our help center for more</a>
            <a href="https://shop.example/unsubscribe?u=1">Log in to unsubscribe</a>
            <a href="mailto:help@shop.example">Sign in</a>
        """.trimIndent()

        assertThat(findSignInLinkInHtml(html)).isNull()
    }

    @Test
    fun `a plain-text mail should give the address after the words that name it`() {
        val text = """
            Hello,

            Use the link below to sign in to your account:
            https://app.example/auth/magic?token=xyz.

            If you did not ask for this, ignore this mail. Manage alerts at https://app.example/preferences
        """.trimIndent()

        assertThat(findSignInLinkInText(text)).isEqualTo("https://app.example/auth/magic?token=xyz")
    }

    @Test
    fun `a plain-text address with nothing about signing in before it should be left alone`() {
        val text = "Your weekly report is ready: https://app.example/reports/7"

        assertThat(findSignInLinkInText(text)).isNull()
    }

    @Test
    fun `a plain-text message should be read when it has no html`() {
        val testSubject = buildMessage {
            header("Content-Type", "text/plain; charset=utf-8")
            textBody("Click to verify your email:\nhttps://app.example/verify/9\n")
        }

        val link = findSignInLink(testSubject, subject = "Verify your email")

        assertThat(link).isEqualTo("https://app.example/verify/9")
    }

    @Test
    fun `sign-in subjects should be recognised`() {
        assertThat(looksLikeSignInMail("Your sign-in link for Claude")).isTrue()
        assertThat(looksLikeSignInMail("Confirm your email address on Slack")).isTrue()
        assertThat(looksLikeSignInMail("Bitte bestätigen Sie Ihre E-Mail-Adresse")).isTrue()
        assertThat(looksLikeSignInMail("Your order has shipped")).isFalse()
        assertThat(looksLikeSignInMail(null)).isFalse()
    }

    private fun htmlMessage(html: String) = buildMessage {
        multipart("alternative") {
            bodyPart("text/plain; charset=utf-8") { textBody("See the HTML version.") }
            bodyPart("text/html; charset=utf-8") { textBody(html) }
        }
    }
}
