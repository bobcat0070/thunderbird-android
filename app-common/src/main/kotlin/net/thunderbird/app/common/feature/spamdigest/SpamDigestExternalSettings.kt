package net.thunderbird.app.common.feature.spamdigest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.fsck.k9.preferences.ExternalGlobalSettings
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_ALERT_ACCOUNTS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_DIGEST_SENDER_ACCOUNT_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_DIGEST_SEND_TIME_KEY
import java.util.Locale
import net.thunderbird.feature.spamdigest.SpamDigestAccounts
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import net.thunderbird.feature.spamdigest.SpamDigestTime
import net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync
import org.json.JSONArray

private const val PENDING_PREFERENCES_NAME = "spam_digest_import"

/**
 * Carries the spam digest and spam alert settings through settings export.
 *
 * Accounts are named by email address, not by id: an imported account gets a new id when one with the same id is
 * already on the device. And because an import writes these global settings before it creates the accounts, the
 * values are kept as pending and applied by [PendingSpamDigestImport] once the accounts they name exist.
 */
internal class SpamDigestExternalSettings(
    private val settingsRepository: SpamDigestSettingsRepository,
    private val accounts: SpamDigestAccounts,
    private val pendingImport: PendingSpamDigestImport,
) : ExternalGlobalSettings {

    override val keys: Set<String> = setOf(
        SPAM_DIGEST_SENDER_ACCOUNT_KEY,
        SPAM_DIGEST_SEND_TIME_KEY,
        SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY,
        SPAM_ALERT_ACCOUNTS_KEY,
    )

    override fun exportSettings(): Map<String, String> {
        val settings = settingsRepository.getSettings()
        val emailsById = accounts.accounts().associate { it.id to it.email.lowercase() }

        return mapOf(
            SPAM_DIGEST_SENDER_ACCOUNT_KEY to settings.senderAccountId?.let(emailsById::get).orEmpty(),
            SPAM_DIGEST_SEND_TIME_KEY to
                "%02d:%02d".format(Locale.ROOT, settings.sendTime.hour, settings.sendTime.minute),
            SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY to settings.excludedAccountIds.mapNotNull(emailsById::get).toJson(),
            SPAM_ALERT_ACCOUNTS_KEY to settings.alertAccountIds.mapNotNull(emailsById::get).toJson(),
        )
    }

    override fun importSettings(values: Map<String, String>) {
        pendingImport.add(
            senderEmail = values[SPAM_DIGEST_SENDER_ACCOUNT_KEY]?.trim()?.lowercase()?.takeIf { '@' in it },
            sendTime = values[SPAM_DIGEST_SEND_TIME_KEY]?.toSendTime(),
            excludedEmails = values[SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY]?.parseEmails().orEmpty(),
            alertEmails = values[SPAM_ALERT_ACCOUNTS_KEY]?.parseEmails().orEmpty(),
        )
        pendingImport.applyAvailable()
    }
}

/**
 * Imported spam settings waiting for the accounts they name. Each part is applied as soon as its account exists and
 * then forgotten; the send time needs no account and is applied straight away.
 */
internal class PendingSpamDigestImport(
    context: Context,
    private val settingsRepository: SpamDigestSettingsRepository,
    private val accounts: SpamDigestAccounts,
    private val spamFolderBackgroundSync: SpamFolderBackgroundSync,
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PENDING_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun add(senderEmail: String?, sendTime: SpamDigestTime?, excludedEmails: Set<String>, alertEmails: Set<String>) {
        synchronized(lock) {
            sendTime?.let(settingsRepository::setSendTime)
            preferences.edit {
                senderEmail?.let { putString(KEY_SENDER, it) }
                putStringSet(KEY_EXCLUDED, preferences.getStringSet(KEY_EXCLUDED, null).orEmpty() + excludedEmails)
                putStringSet(KEY_ALERTS, preferences.getStringSet(KEY_ALERTS, null).orEmpty() + alertEmails)
            }
        }
    }

    /**
     * Applies whatever names an account that now exists. Cheap when nothing is pending, so it can run whenever the
     * accounts change.
     */
    fun applyAvailable() {
        synchronized(lock) {
            val sender = preferences.getString(KEY_SENDER, null)
            val excluded = preferences.getStringSet(KEY_EXCLUDED, null).orEmpty()
            val alerts = preferences.getStringSet(KEY_ALERTS, null).orEmpty()
            if (sender == null && excluded.isEmpty() && alerts.isEmpty()) return

            val idsByEmail = accounts.accounts().associate { it.email.lowercase() to it.id }

            val senderId = sender?.let(idsByEmail::get)
            senderId?.let(settingsRepository::setSenderAccount)

            val excludedApplied = excluded.filter { email ->
                idsByEmail[email]?.also { settingsRepository.setAccountIncluded(it, included = false) } != null
            }

            // An alert also turns on syncing of the spam folder, which only works once the folder is known.
            val alertsApplied = alerts.filter { email ->
                val id = idsByEmail[email] ?: return@filter false
                if (spamFolderBackgroundSync.isSyncEnabled(id) == null) return@filter false

                settingsRepository.setAlertEnabled(id, enabled = true)
                true
            }

            preferences.edit {
                if (senderId != null) remove(KEY_SENDER)
                putStringSet(KEY_EXCLUDED, excluded - excludedApplied.toSet())
                putStringSet(KEY_ALERTS, alerts - alertsApplied.toSet())
            }
        }
    }

    private companion object {
        const val KEY_SENDER = "sender"
        const val KEY_EXCLUDED = "excluded"
        const val KEY_ALERTS = "alerts"
    }
}

private fun Collection<String>.toJson(): String = JSONArray(sorted()).toString()

@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun String.parseEmails(): Set<String>? {
    if (isBlank()) return emptySet()

    return try {
        val array = JSONArray(this)
        (0 until array.length())
            .mapNotNull { index -> array.optString(index).trim().lowercase().takeIf { '@' in it } }
            .toSet()
    } catch (e: Exception) {
        null
    }
}

private fun String.toSendTime(): SpamDigestTime? = runCatching {
    SpamDigestTime(hour = substringBefore(':').trim().toInt(), minute = substringAfter(':').trim().toInt())
}.getOrNull()
