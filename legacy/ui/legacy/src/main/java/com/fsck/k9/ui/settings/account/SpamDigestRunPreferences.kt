package com.fsck.k9.ui.settings.account

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.feature.spamdigest.SpamDigestHistory
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent
import net.thunderbird.feature.spamdigest.SpamDigestRunEvent.Kind
import net.thunderbird.feature.spamdigest.SpamDigestScheduler

private const val PREFERENCE_SEND_NOW = "spam_digest_send_now"
private const val PREFERENCE_HISTORY = "spam_digest_history"

/**
 * How many of the recent events the screen lists; tapping the list shows all of them.
 */
private const val SHOWN_EVENTS = 10

/**
 * The spam digest items that do something rather than hold a setting: sending a digest now, and what the recent runs
 * did - which is how a digest that did not arrive can be explained without a debug log.
 *
 * Its own class rather than more of the account settings screen, which is already far past the size the project
 * allows.
 */
internal class SpamDigestRunPreferences(
    private val scheduler: SpamDigestScheduler,
    private val history: SpamDigestHistory,
) {
    fun initialize(fragment: PreferenceFragmentCompat) {
        fragment.findPreference<Preference>(PREFERENCE_SEND_NOW)?.setOnPreferenceClickListener {
            sendNow(fragment)
            true
        }
        fragment.findPreference<Preference>(PREFERENCE_HISTORY)?.setOnPreferenceClickListener {
            showAllEvents(fragment)
            true
        }
        showHistory(fragment)
    }

    /**
     * Shows the recent runs again, for when the screen comes back into view.
     */
    fun showHistory(fragment: PreferenceFragmentCompat) {
        val preference = fragment.findPreference<Preference>(PREFERENCE_HISTORY) ?: return
        val context = fragment.requireContext()

        fragment.lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) { history.recentEvents() }
            preference.summary = if (events.isEmpty()) {
                context.getString(R.string.account_settings_spam_digest_history_empty)
            } else {
                context.describe(events.take(SHOWN_EVENTS))
            }
        }
    }

    private fun showAllEvents(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()

        fragment.lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) { history.recentEvents() }
            showHistory(fragment)
            if (events.isEmpty()) return@launch

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.account_settings_spam_digest_history_label)
                .setMessage(context.describe(events))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun sendNow(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext().applicationContext

        fragment.lifecycleScope.launch {
            // Asks WorkManager what is already running, which waits on its database.
            withContext(Dispatchers.IO) { scheduler.sendNow() }
            Toast.makeText(context, R.string.account_settings_spam_digest_send_now_requested, Toast.LENGTH_SHORT)
                .show()
            showHistory(fragment)
        }
    }

    private fun Context.describe(events: List<SpamDigestRunEvent>): String {
        return events.joinToString("\n") { event ->
            getString(R.string.account_settings_spam_digest_history_event, formatTime(event), describeKind(event))
        }
    }

    /**
     * To the second: the steps of one run are seconds apart, and how far apart is part of what went wrong.
     */
    private fun Context.formatTime(event: SpamDigestRunEvent): String {
        val millis = event.at.toEpochMilliseconds()
        val day = DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

        return "$day, $time"
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun Context.describeKind(event: SpamDigestRunEvent): String {
        val detail = event.detail
        val values = detail?.split(SpamDigestRunEvent.DETAIL_SEPARATOR).orEmpty()
        fun value(index: Int): String = values.getOrNull(index).orEmpty()
        val error = detail ?: getString(R.string.account_settings_spam_digest_history_unknown_error)

        return when (event.kind) {
            Kind.ALARM -> getString(R.string.account_settings_spam_digest_history_alarm)
            Kind.CATCH_UP -> getString(R.string.account_settings_spam_digest_history_catch_up)
            Kind.REQUESTED -> getString(R.string.account_settings_spam_digest_history_requested)
            Kind.STARTED -> getString(R.string.account_settings_spam_digest_history_started, detail.orEmpty())
            Kind.READ -> describeRead(values)
            Kind.SENDING -> getString(
                R.string.account_settings_spam_digest_history_sending,
                messages(value(0).toIntOrNull() ?: 0),
                value(1),
            )
            Kind.SENT -> if (values.size > 1) {
                getString(R.string.account_settings_spam_digest_history_sent_in, value(0), value(1))
            } else {
                getString(R.string.account_settings_spam_digest_history_sent, detail.orEmpty())
            }

            Kind.ALREADY_SENT -> if (detail != null) {
                getString(R.string.account_settings_spam_digest_history_already_sent, detail)
            } else {
                getString(R.string.account_settings_spam_digest_history_nothing_due)
            }

            Kind.OFF -> getString(R.string.account_settings_spam_digest_history_off)
            Kind.SENDER_MISSING -> getString(R.string.account_settings_spam_digest_history_sender_missing)
            Kind.FAILED -> getString(R.string.account_settings_spam_digest_history_failed, error)
            Kind.GAVE_UP -> getString(R.string.account_settings_spam_digest_history_gave_up, error)
            Kind.STOPPED -> getString(R.string.account_settings_spam_digest_history_stopped, error)
            Kind.WAITING -> getString(R.string.account_settings_spam_digest_history_waiting)
            Kind.SCHEDULED -> describeScheduled(values)
        }
    }

    /**
     * The values of a [Kind.READ] detail, in order; see [SpamDigestRunEvent.Kind.READ].
     */
    private enum class ReadValue { MESSAGES, ACCOUNTS, NOT_REFRESHED, UNREADABLE, NO_SPAM_FOLDER }

    private fun Context.describeRead(values: List<String>): String {
        fun count(value: ReadValue): Int = values.getOrNull(value.ordinal)?.toIntOrNull() ?: 0
        val notRefreshed = count(ReadValue.NOT_REFRESHED)
        val unreadable = count(ReadValue.UNREADABLE)
        val noSpamFolder = count(ReadValue.NO_SPAM_FOLDER)

        return buildString {
            append(
                getString(
                    R.string.account_settings_spam_digest_history_read,
                    messages(count(ReadValue.MESSAGES)),
                    accounts(count(ReadValue.ACCOUNTS)),
                ),
            )
            if (notRefreshed > 0) {
                append(getString(R.string.account_settings_spam_digest_history_read_not_refreshed, notRefreshed))
            }
            if (unreadable > 0) {
                append(getString(R.string.account_settings_spam_digest_history_read_unreadable, unreadable))
            }
            if (noSpamFolder > 0) {
                append(getString(R.string.account_settings_spam_digest_history_read_no_spam_folder, noSpamFolder))
            }
        }
    }

    private fun Context.messages(count: Int): String =
        resources.getQuantityString(R.plurals.account_settings_spam_digest_history_messages, count, count)

    private fun Context.accounts(count: Int): String =
        resources.getQuantityString(R.plurals.account_settings_spam_digest_history_accounts, count, count)

    private fun Context.describeScheduled(values: List<String>): String {
        val millis = values.getOrNull(0)?.toLongOrNull()
            ?: return getString(R.string.account_settings_spam_digest_history_scheduled_unknown)
        val time = DateUtils.formatDateTime(
            this,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )

        return if (values.getOrNull(1) == SpamDigestRunEvent.EXACT_ALARM) {
            getString(R.string.account_settings_spam_digest_history_scheduled_exact, time)
        } else {
            getString(R.string.account_settings_spam_digest_history_scheduled_inexact, time)
        }
    }
}
