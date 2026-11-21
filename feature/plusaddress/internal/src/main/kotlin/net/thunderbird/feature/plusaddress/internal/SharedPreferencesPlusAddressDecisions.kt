package net.thunderbird.feature.plusaddress.internal

import android.content.Context
import androidx.core.content.edit
import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import net.thunderbird.feature.plusaddress.PlusAddressDecisions
import net.thunderbird.feature.plusaddress.PlusAddressSettings

/**
 * What an imported plus address must look like; anything else is dropped.
 */
private val PLUS_ADDRESS = Regex("[^\\s@|+]+\\+[^\\s@|]+@[a-z0-9.-]+\\.[a-z0-9-]+")

/**
 * What an imported sender domain must look like; anything else is dropped.
 */
private val DOMAIN = Regex("[a-z0-9-]+(\\.[a-z0-9-]+)+")

/**
 * The reader's decisions as the leak check reads them.
 */
internal interface PlusAddressDecisionStore : PlusAddressDecisions {
    fun decisions(): PlusAddressDecisionSnapshot
}

/**
 * Keeps the reader's decisions about their plus addresses in the app's private preferences.
 */
internal class SharedPreferencesPlusAddressDecisions(
    context: Context,
) : PlusAddressDecisionStore, PlusAddressSettings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun decisions(): PlusAddressDecisionSnapshot = PlusAddressDecisionSnapshot(
        allowedSenders = allowedEntries()
            .groupBy(keySelector = { (address, _) -> address }, valueTransform = { (_, domain) -> domain })
            .mapValues { (_, domains) -> domains.toSet() },
        ignored = preferences.getStringSet(IGNORED, emptySet()).orEmpty().toSet(),
    )

    override fun allowSender(plusAddress: String, domain: String) {
        val entry = plusAddress.lowercase() to domain.lowercase()
        writeAllowed(allowedEntries() + entry)
    }

    override fun setIgnored(plusAddress: String, isIgnored: Boolean) {
        val address = plusAddress.lowercase()
        val ignored = preferences.getStringSet(IGNORED, emptySet()).orEmpty()
        preferences.edit { putStringSet(IGNORED, if (isIgnored) ignored + address else ignored - address) }
    }

    override fun exportDecisions(): PlusAddressDecisionSnapshot = decisions()

    override fun importDecisions(decisions: PlusAddressDecisionSnapshot) {
        val allowed = decisions.allowedSenders
            .flatMap { (address, domains) -> domains.map { domain -> address.lowercase() to domain.lowercase() } }
            .filter { (address, domain) -> PLUS_ADDRESS.matches(address) && DOMAIN.matches(domain) }
        if (allowed.isNotEmpty()) writeAllowed(allowedEntries() + allowed)

        val ignored = decisions.ignored.map { it.lowercase() }.filter { PLUS_ADDRESS.matches(it) }
        if (ignored.isNotEmpty()) {
            val current = preferences.getStringSet(IGNORED, emptySet()).orEmpty()
            preferences.edit { putStringSet(IGNORED, current + ignored) }
        }
    }

    private fun allowedEntries(): Set<Pair<String, String>> {
        return preferences.getStringSet(ALLOWED, emptySet()).orEmpty()
            .mapNotNullTo(mutableSetOf()) { entry ->
                val address = entry.substringBefore(SEPARATOR)
                val domain = entry.substringAfter(SEPARATOR, missingDelimiterValue = "")
                (address to domain).takeIf { address.isNotEmpty() && domain.isNotEmpty() }
            }
    }

    private fun writeAllowed(entries: Set<Pair<String, String>>) {
        preferences.edit {
            putStringSet(ALLOWED, entries.mapTo(mutableSetOf()) { (address, domain) -> "$address$SEPARATOR$domain" })
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "plus_addresses"
        const val ALLOWED = "allowed_senders"
        const val IGNORED = "ignored"
        const val SEPARATOR = "|"
    }
}
