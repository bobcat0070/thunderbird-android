package com.fsck.k9.ui.messageview

import android.content.Context
import android.content.SharedPreferences

private const val PREFERENCES_NAME = "remote_image_senders"
private const val KEY_SENDERS = "senders"
private const val KEY_DOMAINS = "domains"

/**
 * How widely a decision to load remote images applies.
 */
enum class RemoteImageScope {
    /** One exact address. */
    SENDER,

    /** Every address at a domain, for senders that rotate the local part. */
    DOMAIN,
}

/**
 * Remembers the senders whose remote images the user is happy to load.
 *
 * Remote images are held back because loading one tells the sender the message was opened, and by whom and
 * when. That is worth a prompt for a stranger and pure friction for a shop the user buys from every week, so
 * the decision is theirs to record.
 *
 * Deliberately an allowlist and never a blocklist: the safe answer is the absence of an entry, so a lost or
 * corrupt store fails closed and starts asking again rather than silently loading everything.
 */
class RemoteImageSenderStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * @return whether remote images should load for [emailAddress] without asking.
     */
    fun isTrusted(emailAddress: String): Boolean {
        val address = emailAddress.trim().lowercase()
        if (address.isEmpty()) return false

        val domain = address.emailDomainOrNull()

        return address in read(KEY_SENDERS) || (domain != null && domain in read(KEY_DOMAINS))
    }

    fun trust(emailAddress: String, scope: RemoteImageScope) {
        val value = valueFor(emailAddress, scope) ?: return

        write(scope.key(), read(scope.key()) + value)
    }

    fun forget(emailAddress: String, scope: RemoteImageScope) {
        val value = valueFor(emailAddress, scope) ?: return

        write(scope.key(), read(scope.key()) - value)
    }

    private fun valueFor(emailAddress: String, scope: RemoteImageScope): String? {
        val address = emailAddress.trim().lowercase()
        if (address.isEmpty()) return null

        return when (scope) {
            RemoteImageScope.SENDER -> address
            RemoteImageScope.DOMAIN -> address.emailDomainOrNull()
        }
    }

    private fun RemoteImageScope.key(): String = when (this) {
        RemoteImageScope.SENDER -> KEY_SENDERS
        RemoteImageScope.DOMAIN -> KEY_DOMAINS
    }

    // The returned set is owned by SharedPreferences and must not be mutated, so every write builds a new one.
    private fun read(key: String): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty()

    private fun write(key: String, values: Set<String>) {
        preferences.edit().putStringSet(key, values.toSet()).apply()
    }
}

/**
 * @return the address's domain, lower-cased, or `null` when this is not an address with a domain.
 */
internal fun String.emailDomainOrNull(): String? {
    val domain = substringAfterLast('@', missingDelimiterValue = "").trim().lowercase()

    return domain.ifEmpty { null }
}
