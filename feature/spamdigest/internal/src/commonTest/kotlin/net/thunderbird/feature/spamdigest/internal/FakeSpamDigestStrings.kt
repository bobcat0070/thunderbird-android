package net.thunderbird.feature.spamdigest.internal

import kotlinx.datetime.LocalDate

internal class FakeSpamDigestStrings : SpamDigestStrings {
    override fun formatDate(date: LocalDate): String = date.toString()
    override fun subject(date: String, messageCount: Int): String = "Spam digest for $date: $messageCount"
    override fun intro(date: String, messageCount: Int, accountCount: Int): String =
        "Spam on $date: $messageCount in $accountCount"
    override fun accountMessageCount(messageCount: Int): String = "$messageCount messages"
    override fun noMessages(): String = "No spam."
    override fun noSpamFolder(): String = "No spam folder."
    override fun unreadable(): String = "Unreadable."
    override fun notRefreshed(): String = "Not refreshed."
    override fun unknownSenderName(): String = "(no name)"
    override fun unknownSenderAddress(): String = "(no address)"
    override fun noSubject(): String = "(no subject)"
    override fun subjectLine(subject: String): String = "Subject: $subject"
    override fun checksNotReported(): String = "not reported"
    override fun legend(): String = "Legend."
}
