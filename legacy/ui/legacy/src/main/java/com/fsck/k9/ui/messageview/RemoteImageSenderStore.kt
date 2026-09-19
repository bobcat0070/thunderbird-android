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
     *
     * @param isSenderAuthenticated whether the message passed DMARC for this address's domain. A domain-wide
     *   decision only applies when it did: anyone can write an address at a trusted domain into From, and
     *   without the check a spoofed message would get its tracking images loaded on the strength of mail the
     *   user actually trusted. A single trusted address is honoured either way, as a contact is, because a
     *   person's own small domain often publishes no DMARC policy at all.
     */
    @Suppress("ReturnCount")
    fun isTrusted(emailAddress: String, isSenderAuthenticated: Boolean): Boolean {
        val address = emailAddress.trim().lowercase()
        if (address.isEmpty()) return false
        if (address in read(KEY_SENDERS)) return true

        val domain = address.emailDomainOrNull() ?: return false

        return isSenderAuthenticated && domain in read(KEY_DOMAINS)
    }

    /**
     * @return everything the user has trusted, so it can be reviewed and taken back.
     */
    fun trusted(): List<Pair<RemoteImageScope, String>> {
        val senders = read(KEY_SENDERS).map { RemoteImageScope.SENDER to it }
        val domains = read(KEY_DOMAINS).map { RemoteImageScope.DOMAIN to it }

        return (senders + domains).sortedBy { (_, pattern) -> pattern }
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
