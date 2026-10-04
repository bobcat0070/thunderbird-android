package net.thunderbird.feature.spamdigest.internal

import android.content.Context
import androidx.core.os.ConfigurationCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

internal class AndroidSpamDigestStrings(
    context: Context,
) : SpamDigestStrings {
    private val context = context.applicationContext
    private val resources get() = context.resources

    override fun formatDate(date: LocalDate): String {
        val locale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
        val timeZone = TimeZone.currentSystemDefault()
        val startOfDay = Date(date.atStartOfDayIn(timeZone).toEpochMilliseconds())

        return DateFormat.getDateInstance(DateFormat.FULL, locale).format(startOfDay)
    }

    override fun subject(date: String, messageCount: Int): String =
        resources.getQuantityString(R.plurals.spam_digest_subject, messageCount, messageCount, date)

    override fun intro(date: String, messageCount: Int, accountCount: Int): String {
        val messages = resources.getQuantityString(R.plurals.spam_digest_messages, messageCount, messageCount)
        val accounts = resources.getQuantityString(R.plurals.spam_digest_accounts, accountCount, accountCount)

        return resources.getString(R.string.spam_digest_intro, date, messages, accounts)
    }

    override fun accountMessageCount(messageCount: Int): String =
        resources.getQuantityString(R.plurals.spam_digest_messages, messageCount, messageCount)

    override fun noMessages(): String = resources.getString(R.string.spam_digest_no_messages)

    override fun noSpamFolder(): String = resources.getString(R.string.spam_digest_no_spam_folder)

    override fun unreadable(): String = resources.getString(R.string.spam_digest_unreadable)

    override fun notRefreshed(): String = resources.getString(R.string.spam_digest_not_refreshed)

    override fun unknownSenderName(): String = resources.getString(R.string.spam_digest_unknown_sender_name)

    override fun unknownSenderAddress(): String = resources.getString(R.string.spam_digest_unknown_sender_address)

    override fun noSubject(): String = resources.getString(R.string.spam_digest_no_subject)

    override fun subjectLine(subject: String): String = resources.getString(R.string.spam_digest_subject_line, subject)

    override fun checksNotReported(): String = resources.getString(R.string.spam_digest_checks_not_reported)

    override fun legend(): String = resources.getString(R.string.spam_digest_legend)
}
