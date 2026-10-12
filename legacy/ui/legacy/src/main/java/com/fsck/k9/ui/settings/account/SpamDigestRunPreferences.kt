package com.fsck.k9.ui.settings.account

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.fsck.k9.ui.R
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
 * How many of the recent runs the screen lists: enough to see the last few days.
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
            showHistory(fragment)
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
            val events = withContext(Dispatchers.IO) { history.recentEvents().take(SHOWN_EVENTS) }
            preference.summary = if (events.isEmpty()) {
                context.getString(R.string.account_settings_spam_digest_history_empty)
            } else {
                events.joinToString("\n") { event -> context.describe(event) }
            }
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

    private fun Context.describe(event: SpamDigestRunEvent): String {
        val time = DateUtils.formatDateTime(
            this,
            event.at.toEpochMilliseconds(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )

        return getString(R.string.account_settings_spam_digest_history_event, time, describeKind(event))
    }

    @Suppress("CyclomaticComplexMethod")
    private fun Context.describeKind(event: SpamDigestRunEvent): String {
        val detail = event.detail
        val error = detail ?: getString(R.string.account_settings_spam_digest_history_unknown_error)

        return when (event.kind) {
            Kind.ALARM -> getString(R.string.account_settings_spam_digest_history_alarm)
            Kind.CATCH_UP -> getString(R.string.account_settings_spam_digest_history_catch_up)
            Kind.REQUESTED -> getString(R.string.account_settings_spam_digest_history_requested)
            Kind.STARTED -> getString(R.string.account_settings_spam_digest_history_started, detail.orEmpty())
            Kind.SENT -> getString(R.string.account_settings_spam_digest_history_sent, detail.orEmpty())
            Kind.ALREADY_SENT -> if (detail != null) {
                getString(R.string.account_settings_spam_digest_history_already_sent, detail)
            } else {
                getString(R.string.account_settings_spam_digest_history_nothing_due)
            }

            Kind.OFF -> getString(R.string.account_settings_spam_digest_history_off)
            Kind.SENDER_MISSING -> getString(R.string.account_settings_spam_digest_history_sender_missing)
            Kind.FAILED -> getString(R.string.account_settings_spam_digest_history_failed, error)
            Kind.GAVE_UP -> getString(R.string.account_settings_spam_digest_history_gave_up, error)
        }
    }
}
