package net.thunderbird.feature.spamdigest.internal

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.thunderbird.feature.impersonation.Impersonation
import net.thunderbird.feature.spamdigest.SenderCheck
import net.thunderbird.feature.spamdigest.SenderCheckMethod
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamDigestField
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
    fun `a digest covering several days should name them all and list each day's mail under it`() {
        // Made up for the digests that did not go out, so no day's spam goes unreported.
        val secondDay = Instant.parse("2026-10-03T09:00:00Z")
        val accounts = listOf(
            AccountSpam(
                account = work,
                result = SpamFolderResult.Read(
                    SpamFolderContents(
                        messages = listOf(
                            spamMessage("Later", "later@spam.example", "Second day", receivedAt = secondDay),
                            spamMessage("Earlier", "earlier@spam.example", "First day"),
                        ),
                        isRefreshed = true,
                    ),
                ),
            ),
        )

        val result = testSubject.compose(day, accounts, lastDay = LocalDate(2026, 10, 3))

        assertThat(result.subject).isEqualTo("Spam digest for 2026-10-02 to 2026-10-03: 2")
        val body = result.body
        assertThat(body).contains("Spam on 2026-10-02 to 2026-10-03: 2 in 1")
        val firstHeading = body.indexOf("-- 2026-10-02 --")
        val firstMessage = body.indexOf("1. Earlier")
        val secondHeading = body.indexOf("-- 2026-10-03 --")
        val secondMessage = body.indexOf("2. Later")
        assertThat(listOf(firstHeading, firstMessage, secondHeading, secondMessage).all { it >= 0 }).isEqualTo(true)
        assertThat(firstHeading < firstMessage && firstMessage < secondHeading && secondHeading < secondMessage)
            .isEqualTo(true)
    }

    @Test
    fun `a one-day digest should not have day headings`() {
        val accounts = listOf(
            AccountSpam(
                account = work,
                result = SpamFolderResult.Read(
                    SpamFolderContents(listOf(spamMessage("A", "a@spam.example", "One")), isRefreshed = true),
                ),
            ),
        )

        val result = testSubject.compose(day, accounts)

        assertThat(result.body.contains("-- 2026-10-02 --")).isEqualTo(false)
    }

    @Test
    fun `compose should list mail from people the reader knows first, and only there`() {
        val known = spamMessage(
            senderName = "Jordan Colleague",
            senderAddress = "jordan@firm.example",
            subject = "Contract",
        )
            .copy(isFromKnownSender = true)
        val lookalike = spamMessage(senderName = "PayPal", senderAddress = "service@paypa1.com", subject = "Verify")
            .copy(impersonation = Impersonation.LookalikeDomain("paypa1.com", "paypal.com"))
        val accounts = listOf(
            AccountSpam(work, SpamFolderResult.Read(SpamFolderContents(listOf(known, lookalike), isRefreshed = true))),
            AccountSpam(
                personal,
                SpamFolderResult.Read(SpamFolderContents(listOf(known.copy(subject = "Lunch")), isRefreshed = true)),
            ),
        )

        val result = testSubject.compose(day, accounts)

        assertThat(result.subject).isEqualTo("Spam digest for 2026-10-02: 3, 2 known")
        assertThat(result.body).isEqualTo(
            """
            |Spam on 2026-10-02: 3 in 2
            |
            |== From people you know: 2 ==
            |
            |1. Jordan Colleague
            |   jordan@firm.example  (not reported)
            |   Subject: Contract
            |   In: Work <me@work.example>
            |
            |2. Jordan Colleague
            |   jordan@firm.example  (not reported)
            |   Subject: Lunch
            |   In: me@home.example
            |
            |== Work <me@work.example> ==
            |1 messages
            |
            |1. PayPal
            |   service@paypa1.com  (not reported)
            |   ⚠ paypa1.com looks like paypal.com
            |   Subject: Verify
            |
            |== me@home.example ==
            |No other spam.
            |
            |Legend.
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `compose should show only the chosen fields, and no legend without the checks`() {
        val message = spamMessage(senderName = "Acme Deals", senderAddress = "promo@acme.example", subject = "You won")
            .copy(senderChecks = listOf(SenderCheck(SenderCheckMethod.DMARC, passedAligned = false)))
        val accounts = listOf(
            AccountSpam(work, SpamFolderResult.Read(SpamFolderContents(listOf(message), isRefreshed = true))),
        )

        val result = testSubject.compose(day, accounts, setOf(SpamDigestField.SENDER_ADDRESS, SpamDigestField.SUBJECT))

        assertThat(result.body).isEqualTo(
            """
            |Spam on 2026-10-02: 1 in 1
            |
            |== Work <me@work.example> ==
            |1 messages
            |
            |1. promo@acme.example
            |   Subject: You won
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `compose should still name the sender when nothing that identifies a message is chosen`() {
        val message = spamMessage(senderName = "Acme Deals", senderAddress = "promo@acme.example", subject = "You won")
        val accounts = listOf(
            AccountSpam(work, SpamFolderResult.Read(SpamFolderContents(listOf(message), isRefreshed = true))),
        )

        val result = testSubject.compose(day, accounts, setOf(SpamDigestField.SENDER_CHECKS))

        assertThat(result.body.lines()).contains("1. promo@acme.example  (not reported)")
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
        receivedAt: Instant = Instant.parse("2026-10-02T12:00:00Z"),
    ) = SpamMessage(
        senderName = senderName,
        senderAddress = senderAddress,
        subject = subject,
        receivedAt = receivedAt,
        senderChecks = senderChecks,
    )
}
