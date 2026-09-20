package com.fsck.k9.activity.compose

import app.k9mail.legacy.di.DI
import com.fsck.k9.backend.BackendManager
import com.fsck.k9.backend.api.DirectorySearcher
import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.SentMailRecipientScanner
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.preference.GeneralSettingsManager

/**
 * An address worth offering, as the loader needs it.
 */
data class SuggestedRecipient(
    val address: String,
    val displayName: String?,
)

/**
 * The addresses this app has learned, for the field a message is addressed in.
 *
 * An interface so the loader can be built without any of what answering actually takes - a message store, an
 * account manager, a network connection - which is what lets the loader's own tests exercise it against the
 * device's contacts alone.
 */
interface RecipientSuggestions {
    /**
     * @return addresses matching [query] from what is held on this device.
     */
    fun search(query: String, limit: Int): List<SuggestedRecipient>

    /**
     * @return the addresses written to most, for the suggestions offered before anything is typed.
     */
    fun mostUsed(limit: Int): List<SuggestedRecipient>

    /**
     * @return people matching [query] in the directory of each account that has one, or nothing at all when the
     *   user has not asked for directory search.
     */
    fun searchDirectory(query: String): List<SuggestedRecipient>
}

/**
 * Answers from the recipient index, and from each account's directory when asked to.
 *
 * Everything it needs is resolved when a question is actually asked rather than when this is built. Building it
 * is part of building a recipient field, and that must not drag in the message store - a compose screen opening
 * is not a reason to construct a mail backend.
 */
class DefaultRecipientSuggestions(
    private val index: () -> RecipientIndex = { DI.get(RecipientIndex::class.java) },
    private val scanner: () -> SentMailRecipientScanner = { DI.get(SentMailRecipientScanner::class.java) },
    private val settings: () -> GeneralSettingsManager = { DI.get(GeneralSettingsManager::class.java) },
    private val backendManager: () -> BackendManager = { DI.get(BackendManager::class.java) },
    private val accountManager: () -> LegacyAccountDtoManager = { DI.get(LegacyAccountDtoManager::class.java) },
    private val logger: () -> Logger = { DI.get(Logger::class.java) },
) : RecipientSuggestions {

    /**
     * The scan that builds the history is kicked off here rather than on a timer: this is the moment its answer
     * is about to be needed, and it does nothing if a recent scan already ran.
     */
    override fun search(query: String, limit: Int): List<SuggestedRecipient> {
        scanner().scanIfDue()

        return index().search(query, limit).map { SuggestedRecipient(it.address, it.displayName) }
    }

    override fun mostUsed(limit: Int): List<SuggestedRecipient> {
        scanner().scanIfDue()

        return index().mostUsed(limit).map { SuggestedRecipient(it.address, it.displayName) }
    }

    /**
     * Only backends that can answer are asked - in practice the Graph backend, since a mailbox protocol has no
     * notion of an organisation's directory.
     *
     * Results are not stored: they were never the user's address book, and they will be offered again as readily
     * next time. Picking one records it like any other recipient.
     */
    override fun searchDirectory(query: String): List<SuggestedRecipient> {
        if (!settings().getConfig().directorySearch.isEnabled) return emptyList()

        val backendManager = backendManager()

        return accountManager().getAccounts().flatMap { account ->
            val searcher = directorySearcherFor(backendManager, account.uuid) ?: return@flatMap emptyList()

            searcher.searchDirectory(query).flatMap { contact ->
                contact.addresses.map { SuggestedRecipient(it, contact.displayName) }
            }
        }
    }

    /**
     * @return what can search this account's directory, or `null` when nothing can.
     *
     * An account whose backend cannot be built - one being set up, or one whose credentials have expired - is
     * not an error here: completion carries on with what the device already knows.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun directorySearcherFor(backendManager: BackendManager, accountUuid: String): DirectorySearcher? {
        return try {
            backendManager.getBackend(accountUuid) as? DirectorySearcher
        } catch (e: Exception) {
            logger().debug("RecipientSuggestions", e) { "Could not reach a backend to search its directory" }

            null
        }
    }
}
