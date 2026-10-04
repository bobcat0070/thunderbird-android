package net.thunderbird.feature.spamdigest.internal

import kotlinx.datetime.LocalDate
import net.thunderbird.feature.spamdigest.SenderCheck
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamFolderContents

private const val MAX_NAME_LENGTH = 80
private const val MAX_ADDRESS_LENGTH = 120
private const val MAX_SUBJECT_LENGTH = 200
private const val ELLIPSIS = "…"
private const val PASS_MARK = "✓"
private const val FAIL_MARK = "✗"
private const val INDENT = "   "

/**
 * The words of the digest, so they follow the app's language.
 */
internal interface SpamDigestStrings {
    fun formatDate(date: LocalDate): String
    fun subject(date: String, messageCount: Int): String
    fun intro(date: String, messageCount: Int, accountCount: Int): String
    fun accountMessageCount(messageCount: Int): String
    fun noMessages(): String
    fun noSpamFolder(): String
    fun unreadable(): String
    fun notRefreshed(): String
    fun unknownSenderName(): String
    fun unknownSenderAddress(): String
    fun noSubject(): String
    fun subjectLine(subject: String): String
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
 */
internal class SpamDigestComposer(
    private val strings: SpamDigestStrings,
) {
    fun compose(day: LocalDate, accounts: List<AccountSpam>): ComposedDigest {
        val date = strings.formatDate(day)
        val messageCount = accounts.sumOf { it.messageCount() }

        val body = buildString {
            appendLine(strings.intro(date, messageCount, accounts.size))

            for (accountSpam in accounts) {
                appendLine()
                appendAccount(accountSpam)
            }

            appendLine()
            appendLine(strings.legend())
        }

        return ComposedDigest(
            subject = strings.subject(date, messageCount),
            body = body,
        )
    }

    private fun StringBuilder.appendAccount(accountSpam: AccountSpam) {
        val account = accountSpam.account
        val heading = if (account.name.isBlank() || account.name == account.email) {
            account.email
        } else {
            "${account.name} <${account.email}>"
        }
        appendLine("== ${heading.sanitized(MAX_ADDRESS_LENGTH)} ==")

        when (val result = accountSpam.result) {
            SpamFolderResult.NoSpamFolder -> appendLine(strings.noSpamFolder())
            SpamFolderResult.Unreadable -> appendLine(strings.unreadable())
            is SpamFolderResult.Read -> appendContents(result.contents)
        }
    }

    private fun StringBuilder.appendContents(contents: SpamFolderContents) {
        if (!contents.isRefreshed) appendLine(strings.notRefreshed())

        if (contents.messages.isEmpty()) {
            appendLine(strings.noMessages())
            return
        }

        appendLine(strings.accountMessageCount(contents.messages.size))

        contents.messages.forEachIndexed { index, message ->
            val name = message.senderName?.sanitized(MAX_NAME_LENGTH).orEmpty().ifEmpty { strings.unknownSenderName() }
            val address = message.senderAddress?.sanitized(MAX_ADDRESS_LENGTH).orEmpty()
                .ifEmpty { strings.unknownSenderAddress() }
            val subject = message.subject?.sanitized(MAX_SUBJECT_LENGTH).orEmpty().ifEmpty { strings.noSubject() }

            appendLine()
            appendLine("${index + 1}. $name")
            appendLine("$INDENT$address  ${formatChecks(message.senderChecks)}")
            appendLine(INDENT + strings.subjectLine(subject))
        }
    }

    private fun formatChecks(checks: List<SenderCheck>): String {
        if (checks.isEmpty()) return "(${strings.checksNotReported()})"

        return checks.joinToString(separator = "  ") { check ->
            "${check.method.name} ${if (check.passedAligned) PASS_MARK else FAIL_MARK}"
        }
    }

    private fun AccountSpam.messageCount(): Int = (result as? SpamFolderResult.Read)?.contents?.messages?.size ?: 0
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
