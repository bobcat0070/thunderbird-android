package net.thunderbird.feature.spamdigest.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestTime

private const val PREFERENCES_NAME = "spam_digest"
private const val KEY_SENDER_ACCOUNT_ID = "senderAccountId"
private const val KEY_EXCLUDED_ACCOUNT_IDS = "excludedAccountIds"
private const val KEY_SEND_HOUR = "sendHour"
private const val KEY_SEND_MINUTE = "sendMinute"
private const val KEY_LAST_SENT_DAY = "lastSentDay"
private const val KEY_ALERT_ACCOUNT_IDS = "alertAccountIds"
private const val KEY_ALERT_ENABLED_AT_PREFIX = "alertEnabledAt."
private const val KEY_ALERTED = "alerted"
private const val KEY_SYNC_TURNED_ON_BY_ALERT = "syncTurnedOnByAlert"
private const val ALERTED_SEPARATOR = ' '

/**
 * How long a message is remembered as alerted about. Longer than any alert is raised for, so nothing is alerted twice.
 */
private val ALERTED_RETENTION = 7.days

/**
 * Keeps the digest settings in their own preferences file, so clearing or importing the main settings does not
 * entangle them.
 */
internal class SharedPreferencesSpamDigestSettingsStore(
    context: Context,
    private val clock: Clock,
) : SpamDigestSettingsStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    override fun getSettings(): SpamDigestSettings = synchronized(lock) {
        SpamDigestSettings(
            senderAccountId = preferences.getString(KEY_SENDER_ACCOUNT_ID, null),
            excludedAccountIds = preferences.getStringSet(KEY_EXCLUDED_ACCOUNT_IDS, null).orEmpty().toSet(),
            sendTime = readSendTime(),
            alertAccountIds = preferences.getStringSet(KEY_ALERT_ACCOUNT_IDS, null).orEmpty().toSet(),
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

    override fun setAlertEnabled(accountId: String, enabled: Boolean) {
        synchronized(lock) {
            val accounts = getSettings().alertAccountIds
            preferences.edit {
                if (enabled) {
                    putStringSet(KEY_ALERT_ACCOUNT_IDS, accounts + accountId)
                    putLong(KEY_ALERT_ENABLED_AT_PREFIX + accountId, clock.now().toEpochMilliseconds())
                } else {
                    putStringSet(KEY_ALERT_ACCOUNT_IDS, accounts - accountId)
                    remove(KEY_ALERT_ENABLED_AT_PREFIX + accountId)
                }
            }
        }
    }

    override fun alertEnabledAt(accountId: String): Instant? = synchronized(lock) {
        if (accountId !in getSettings().alertAccountIds) return null
        val millis = preferences.getLong(KEY_ALERT_ENABLED_AT_PREFIX + accountId, -1L).takeIf { it >= 0 }

        millis?.let { Instant.fromEpochMilliseconds(it) }
    }

    /**
     * Kept as "millis key" entries in one string set; a handful a day at most, pruned on every write.
     */
    override fun markAlerted(key: String, at: Instant): Boolean = synchronized(lock) {
        val oldest = (at - ALERTED_RETENTION).toEpochMilliseconds()
        val entries = preferences.getStringSet(KEY_ALERTED, null).orEmpty()
            .filter { entry -> (entry.substringBefore(ALERTED_SEPARATOR).toLongOrNull() ?: 0L) >= oldest }
        if (entries.any { it.substringAfter(ALERTED_SEPARATOR) == key }) return false

        preferences.edit {
            putStringSet(KEY_ALERTED, (entries + "${at.toEpochMilliseconds()}$ALERTED_SEPARATOR$key").toSet())
        }
        true
    }

    override fun isSyncTurnedOnByAlert(accountId: String): Boolean =
        accountId in preferences.getStringSet(KEY_SYNC_TURNED_ON_BY_ALERT, null).orEmpty()

    override fun setSyncTurnedOnByAlert(accountId: String, turnedOn: Boolean) {
        synchronized(lock) {
            val accounts = preferences.getStringSet(KEY_SYNC_TURNED_ON_BY_ALERT, null).orEmpty().toSet()
            preferences.edit {
                putStringSet(KEY_SYNC_TURNED_ON_BY_ALERT, if (turnedOn) accounts + accountId else accounts - accountId)
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
