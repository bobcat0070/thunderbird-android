package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.thunderbird.feature.spamdigest.SenderCheck
import net.thunderbird.feature.spamdigest.SenderCheckMethod
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamFolderContents
import net.thunderbird.feature.spamdigest.SpamMessage

class SpamDigestComposerTest {
    private val testSubject = SpamDigestComposer(FakeSpamDigestStrings())

    private val day = LocalDate(2026, 10, 2)
    private val work = SpamDigestAccount(id = "work", name = "Work", email = "me@work.example")
    private val personal = SpamDigestAccount(id = "personal", name = "me@home.example", email = "me@home.example")

    @Test
    fun `compose should list name, address with checks, and subject of every message`() {
        val accounts = listOf(
            AccountSpam(
                account = work,
                result = SpamFolderResult.Read(
                    SpamFolderContents(
                        messages = listOf(
                            spamMessage(
                                senderName = "Acme Deals",
                                senderAddress = "promo@acme.example",
                                subject = "You won",
                                senderChecks = listOf(
                                    SenderCheck(SenderCheckMethod.SPF, passedAligned = true),
                                    SenderCheck(SenderCheckMethod.DKIM, passedAligned = false),
                                    SenderCheck(SenderCheckMethod.DMARC, passedAligned = false),
                                ),
                            ),
                            spamMessage(senderName = null, senderAddress = null, subject = null),
                        ),
                        isRefreshed = true,
                    ),
                ),
            ),
            AccountSpam(personal, SpamFolderResult.Read(SpamFolderContents(emptyList(), isRefreshed = false))),
            AccountSpam(personal.copy(id = "other"), SpamFolderResult.NoSpamFolder),
        )

        val result = testSubject.compose(day, accounts)

        assertThat(result.subject).isEqualTo("Spam digest for 2026-10-02: 2")
        assertThat(result.body).isEqualTo(
            """
            |Spam on 2026-10-02: 2 in 3
            |
            |== Work <me@work.example> ==
            |2 messages
            |
            |1. Acme Deals
            |   promo@acme.example  SPF ✓  DKIM ✗  DMARC ✗
            |   Subject: You won
            |
            |2. (no name)
            |   (no address)  (not reported)
            |   Subject: (no subject)
            |
            |== me@home.example ==
            |Not refreshed.
            |No spam.
            |
            |== me@home.example ==
            |No spam folder.
            |
            |Legend.
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `compose should keep a sender's line breaks from forging entries of their own`() {
        val accounts = listOf(
            AccountSpam(
                account = work,
                result = SpamFolderResult.Read(
                    SpamFolderContents(
                        messages = listOf(
                            spamMessage(
                                senderName = "Bank\r\n2. Your Bank",
                                senderAddress = "a@b.example",
                                subject = "Hi\n   bank@bank.example  SPF ✓  DKIM ✓  DMARC ✓",
                            ),
                        ),
                        isRefreshed = true,
                    ),
                ),
            ),
        )

        val result = testSubject.compose(day, accounts)

        assertThat(result.body.lines().filter { it.startsWith("1. ") || it.startsWith("2. ") })
            .isEqualTo(listOf("1. Bank 2. Your Bank"))
    }

    @Test
    fun `sanitized should drop bidi overrides and other invisible characters`() {
        val result = "‮gnp.exe‬​ Invoice".sanitized(maxLength = 80)

        assertThat(result).isEqualTo("gnp.exe Invoice")
    }

    @Test
    fun `sanitized should cut long text and mark the cut`() {
        val result = "abcdefghij".sanitized(maxLength = 4)

        assertThat(result).isEqualTo("abcd…")
    }

    @Test
    fun `sanitized should not split a surrogate pair when cutting`() {
        val result = "ab😀cd".sanitized(maxLength = 3)

        assertThat(result).isEqualTo("ab…")
    }

    private fun spamMessage(
        senderName: String?,
        senderAddress: String?,
        subject: String?,
        senderChecks: List<SenderCheck> = emptyList(),
    ) = SpamMessage(
        senderName = senderName,
        senderAddress = senderAddress,
        subject = subject,
        receivedAt = Instant.parse("2026-10-02T12:00:00Z"),
        senderChecks = senderChecks,
    )
}
