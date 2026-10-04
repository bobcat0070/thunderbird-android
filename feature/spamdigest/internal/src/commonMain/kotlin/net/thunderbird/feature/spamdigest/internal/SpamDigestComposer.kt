package net.thunderbird.feature.spamdigest.internal

import kotlinx.datetime.LocalDate
import net.thunderbird.feature.impersonation.Impersonation
import net.thunderbird.feature.spamdigest.SenderCheck
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamFolderContents
import net.thunderbird.feature.spamdigest.SpamMessage

private const val MAX_NAME_LENGTH = 80
private const val MAX_ADDRESS_LENGTH = 120
private const val MAX_SUBJECT_LENGTH = 200
private const val ELLIPSIS = "…"
private const val PASS_MARK = "✓"
private const val FAIL_MARK = "✗"
private const val INDENT = "   "
private const val WARNING_MARK = "⚠"

/**
 * The words of the digest, so they follow the app's language.
 */
@Suppress("TooManyFunctions")
internal interface SpamDigestStrings {
    fun formatDate(date: LocalDate): String
    fun subject(date: String, messageCount: Int, knownSenderCount: Int): String
    fun intro(date: String, messageCount: Int, accountCount: Int): String
    fun knownSendersHeading(messageCount: Int): String
    fun accountMessageCount(messageCount: Int): String
    fun noMessages(): String
    fun noOtherMessages(): String
    fun noSpamFolder(): String
    fun unreadable(): String
    fun notRefreshed(): String
    fun unknownSenderName(): String
    fun unknownSenderAddress(): String
    fun noSubject(): String
    fun subjectLine(subject: String): String
    fun accountLine(account: String): String
    fun impersonation(impersonation: Impersonation): String
    fun checksNotReported(): String
    fun legend(): String
}

/**
 * What the digest found for one account.
 */
internal data class AccountSpam(
    val account: SpamDigestAccount,
    val result: SpamFolderResult,
)

internal sealed interface SpamFolderResult {
    data object NoSpamFolder : SpamFolderResult
    data object Unreadable : SpamFolderResult
    data class Read(val contents: SpamFolderContents) : SpamFolderResult
}

internal data class ComposedDigest(
    val subject: String,
    val body: String,
)

/**
 * Writes the digest as plain text.
 *
 * Plain text on purpose: every name, address and subject in it was chosen by a spammer, and an HTML digest would
 * hand them markup in a message the reader trusts because the app sent it.
 *
 * Mail from people the reader knows comes first, under its own heading, because that is the mail a spam filter
 * getting it wrong actually costs something. It is listed there instead of under its account, not as well.
 */
internal class SpamDigestComposer(
    private val strings: SpamDigestStrings,
) {
    fun compose(day: LocalDate, accounts: List<AccountSpam>): ComposedDigest {
        val date = strings.formatDate(day)
        val messageCount = accounts.sumOf { it.messages().size }
        val fromKnownSenders = accounts.flatMap { accountSpam ->
            accountSpam.messages().filter { it.isFromKnownSender }.map { accountSpam.account to it }
        }

        val body = buildString {
            appendLine(strings.intro(date, messageCount, accounts.size))

            if (fromKnownSenders.isNotEmpty()) {
                appendLine()
                appendLine("== ${strings.knownSendersHeading(fromKnownSenders.size)} ==")
                fromKnownSenders.forEachIndexed { index, (account, message) ->
                    appendMessage(index, message, account)
                }
            }

            for (accountSpam in accounts) {
                appendLine()
                appendAccount(accountSpam)
            }

            appendLine()
            appendLine(strings.legend())
        }

        return ComposedDigest(
            subject = strings.subject(date, messageCount, fromKnownSenders.size),
            body = body,
        )
    }

    private fun StringBuilder.appendAccount(accountSpam: AccountSpam) {
        appendLine("== ${accountSpam.account.heading()} ==")

        when (val result = accountSpam.result) {
            SpamFolderResult.NoSpamFolder -> appendLine(strings.noSpamFolder())
            SpamFolderResult.Unreadable -> appendLine(strings.unreadable())
            is SpamFolderResult.Read -> appendContents(result.contents)
        }
    }

    private fun StringBuilder.appendContents(contents: SpamFolderContents) {
        if (!contents.isRefreshed) appendLine(strings.notRefreshed())

        val others = contents.messages.filterNot { it.isFromKnownSender }
        when {
            contents.messages.isEmpty() -> appendLine(strings.noMessages())

            others.isEmpty() -> appendLine(strings.noOtherMessages())

            else -> {
                appendLine(strings.accountMessageCount(others.size))
                others.forEachIndexed { index, message -> appendMessage(index, message, account = null) }
            }
        }
    }

    /**
     * @param account the account the message is in, named when the list it appears in is not already that
     *   account's.
     */
    private fun StringBuilder.appendMessage(index: Int, message: SpamMessage, account: SpamDigestAccount?) {
        val name = message.senderName?.sanitized(MAX_NAME_LENGTH).orEmpty().ifEmpty { strings.unknownSenderName() }
        val address = message.senderAddress?.sanitized(MAX_ADDRESS_LENGTH).orEmpty()
            .ifEmpty { strings.unknownSenderAddress() }
        val subject = message.subject?.sanitized(MAX_SUBJECT_LENGTH).orEmpty().ifEmpty { strings.noSubject() }

        appendLine()
        appendLine("${index + 1}. $name")
        appendLine("$INDENT$address  ${formatChecks(message.senderChecks)}")
        message.impersonation?.let { impersonation ->
            // Built from what the sender wrote, so it goes through the same cleaning as everything else they wrote.
            appendLine("$INDENT$WARNING_MARK ${strings.impersonation(impersonation).sanitized(MAX_SUBJECT_LENGTH)}")
        }
        appendLine(INDENT + strings.subjectLine(subject))
        account?.let { appendLine(INDENT + strings.accountLine(it.heading())) }
    }

    private fun SpamDigestAccount.heading(): String {
        val heading = if (name.isBlank() || name == email) email else "$name <$email>"
        return heading.sanitized(MAX_ADDRESS_LENGTH)
    }

    private fun formatChecks(checks: List<SenderCheck>): String {
        if (checks.isEmpty()) return "(${strings.checksNotReported()})"

        return checks.joinToString(separator = "  ") { check ->
            "${check.method.name} ${if (check.passedAligned) PASS_MARK else FAIL_MARK}"
        }
    }

    private fun AccountSpam.messages(): List<SpamMessage> =
        (result as? SpamFolderResult.Read)?.contents?.messages.orEmpty()
}

/**
 * Makes text a sender chose safe to put on one line of the digest.
 *
 * Line breaks would let a subject forge whole entries of its own, and invisible format characters - the bidi
 * overrides in particular - let a name display as something other than what it is. Both are dropped, runs of
 * whitespace are collapsed, and overly long values are cut.
 */
internal fun String.sanitized(maxLength: Int): String {
    val cleaned = buildString(length) {
        var lastWasSpace = false
        for (character in this@sanitized) {
            when {
                character.isWhitespace() || character.category == CharCategory.CONTROL ||
                    character.category == CharCategory.LINE_SEPARATOR ||
                    character.category == CharCategory.PARAGRAPH_SEPARATOR -> {
                    if (!lastWasSpace) append(' ')
                    lastWasSpace = true
                }

                character.category == CharCategory.FORMAT -> Unit

                else -> {
                    append(character)
                    lastWasSpace = false
                }
            }
        }
    }.trim()

    if (cleaned.length <= maxLength) return cleaned

    var end = maxLength
    if (cleaned[end - 1].isHighSurrogate()) end--

    return cleaned.substring(0, end).trimEnd() + ELLIPSIS
}
