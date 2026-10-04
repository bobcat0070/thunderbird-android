package net.thunderbird.feature.spamdigest.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.datetime.LocalDate
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestTime

private const val PREFERENCES_NAME = "spam_digest"
private const val KEY_SENDER_ACCOUNT_ID = "senderAccountId"
private const val KEY_EXCLUDED_ACCOUNT_IDS = "excludedAccountIds"
private const val KEY_SEND_HOUR = "sendHour"
private const val KEY_SEND_MINUTE = "sendMinute"
private const val KEY_LAST_SENT_DAY = "lastSentDay"

/**
 * Keeps the digest settings in their own preferences file, so clearing or importing the main settings does not
 * entangle them.
 */
internal class SharedPreferencesSpamDigestSettingsStore(
    context: Context,
) : SpamDigestSettingsStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    override fun getSettings(): SpamDigestSettings = synchronized(lock) {
        SpamDigestSettings(
            senderAccountId = preferences.getString(KEY_SENDER_ACCOUNT_ID, null),
            excludedAccountIds = preferences.getStringSet(KEY_EXCLUDED_ACCOUNT_IDS, null).orEmpty().toSet(),
            sendTime = readSendTime(),
        )
    }

    override fun setAccountIncluded(accountId: String, included: Boolean) {
        synchronized(lock) {
            val excluded = getSettings().excludedAccountIds
            val updated = if (included) excluded - accountId else excluded + accountId
            preferences.edit { putStringSet(KEY_EXCLUDED_ACCOUNT_IDS, updated) }
        }
    }

    override fun setSenderAccount(accountId: String?) {
        synchronized(lock) {
            preferences.edit {
                if (accountId == null) remove(KEY_SENDER_ACCOUNT_ID) else putString(KEY_SENDER_ACCOUNT_ID, accountId)
            }
        }
    }

    override fun setSendTime(time: SpamDigestTime) {
        synchronized(lock) {
            preferences.edit {
                putInt(KEY_SEND_HOUR, time.hour)
                putInt(KEY_SEND_MINUTE, time.minute)
            }
        }
    }

    override fun lastSentDay(): LocalDate? {
        val value = preferences.getString(KEY_LAST_SENT_DAY, null) ?: return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }

    override fun markSent(day: LocalDate) {
        preferences.edit { putString(KEY_LAST_SENT_DAY, day.toString()) }
    }

    private fun readSendTime(): SpamDigestTime {
        val hour = preferences.getInt(KEY_SEND_HOUR, SpamDigestTime.DEFAULT.hour)
        val minute = preferences.getInt(KEY_SEND_MINUTE, SpamDigestTime.DEFAULT.minute)

        return runCatching { SpamDigestTime(hour, minute) }.getOrDefault(SpamDigestTime.DEFAULT)
    }
}
