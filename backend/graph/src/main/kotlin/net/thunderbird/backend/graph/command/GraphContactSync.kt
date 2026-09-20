package net.thunderbird.backend.graph.command

import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphContactReader
import net.thunderbird.core.logging.Logger

private const val TAG = "GraphContactSync"

/**
 * One contact as the app outside this backend sees it.
 *
 * Addresses rather than a single address, because a contact commonly has several and any of them is worth
 * completing. Removals carry their addresses too: that is what the caller needs in order to forget them.
 */
data class GraphContactUpdate(
    val displayName: String?,
    val addresses: List<String>,
    val isRemoved: Boolean,
)

/**
 * Where synced contacts are kept, and where the sync resumes from.
 *
 * An interface because storing them is not this module's job: the backend knows how to ask Graph, and the app
 * knows where completion reads from. Implemented outside, supplied when the backend is created, and absent for a
 * backend nobody asked to sync contacts - a test, or an app that does not offer completion at all.
 */
interface GraphContactStore {
    /**
     * @return what the last sync stored, or `null` to read the whole address book.
     */
    fun contactsDeltaLink(): String?

    fun saveContactsDeltaLink(deltaLink: String?)

    /**
     * @return whether enough time has passed to ask again. Asked before any request is made, so a store that
     *   says no costs nothing.
     */
    fun isContactSyncDue(): Boolean

    fun onContactsChanged(contacts: List<GraphContactUpdate>)
}

/**
 * Keeps the mailbox's own contacts in step with what completion reads from.
 *
 * Runs as part of refreshing an account rather than on a schedule of its own: that is already the point where the
 * app has decided to talk to the server, and after the first round a delta call is one cheap request.
 *
 * Failure is deliberately quiet. Contacts make completion better and nothing else depends on them, so an address
 * book that could not be read must not fail the folder refresh that asked for it - the user would see their mail
 * stop syncing because a contact endpoint was unavailable.
 */
internal class GraphContactSync(
    client: GraphApiClient,
    private val store: GraphContactStore?,
    private val logger: Logger,
) {
    private val reader = GraphContactReader(client)

    @Suppress("TooGenericExceptionCaught")
    fun syncIfDue() {
        val store = store ?: return
        if (!store.isContactSyncDue()) return

        try {
            val round = reader.readContacts(store.contactsDeltaLink())

            store.onContactsChanged(
                round.contacts.map { contact ->
                    GraphContactUpdate(
                        displayName = contact.displayName,
                        addresses = contact.addresses,
                        isRemoved = contact.isRemoved,
                    )
                },
            )

            // Stored last, so a round that failed part way through is repeated rather than skipped.
            store.saveContactsDeltaLink(round.deltaLink)
        } catch (e: Exception) {
            logger.debug(TAG, e) { "Could not sync contacts" }
        }
    }

    /**
     * Searches the organisation's directory, for a name completion has not seen.
     *
     * Unlike the sync, this is asked for by someone typing, so a failure is reported as no matches rather than
     * being retried: the field they are typing into cannot wait.
     */
    @Suppress("TooGenericExceptionCaught")
    fun searchDirectory(query: String): List<GraphContactUpdate> {
        return try {
            reader.searchPeople(query).map { person ->
                GraphContactUpdate(
                    displayName = person.displayName,
                    addresses = person.addresses,
                    isRemoved = false,
                )
            }
        } catch (e: Exception) {
            logger.debug(TAG, e) { "Could not search the directory" }
            emptyList()
        }
    }
}
