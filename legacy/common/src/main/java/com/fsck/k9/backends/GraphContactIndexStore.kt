package com.fsck.k9.backends

import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.RemoteContact
import net.thunderbird.backend.graph.command.GraphContactStore
import net.thunderbird.backend.graph.command.GraphContactUpdate

/**
 * How often an account's address book is asked about.
 *
 * A delta call is one cheap request, but a folder refresh can happen every few minutes and an address book does
 * not change that often. An hour keeps a contact added on another device arriving the same session without asking
 * on every sync.
 */
private const val SYNC_INTERVAL_MILLIS = 60L * 60L * 1000L

private const val DELTA_LINK_KEY = "graph_contacts_delta"
private const val LAST_SYNC_KEY = "graph_contacts_synced_at"

/**
 * Keeps a Graph account's own contacts in the index that address completion reads from.
 *
 * The backend knows how to ask Graph and this knows where the answer belongs, which is why the two are separate:
 * the same index also holds what the user's sent mail implies and is read by a compose screen that knows nothing
 * about Graph.
 */
class GraphContactIndexStore(
    private val accountUuid: String,
    private val index: RecipientIndex,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) : GraphContactStore {

    override fun contactsDeltaLink(): String? = index.syncState(accountUuid, DELTA_LINK_KEY)

    override fun saveContactsDeltaLink(deltaLink: String?) {
        index.setSyncState(accountUuid, DELTA_LINK_KEY, deltaLink)
        index.setSyncState(accountUuid, LAST_SYNC_KEY, currentTimeMillis().toString())
    }

    override fun isContactSyncDue(): Boolean {
        val lastSync = index.syncState(accountUuid, LAST_SYNC_KEY)?.toLongOrNull() ?: return true

        return currentTimeMillis() - lastSync >= SYNC_INTERVAL_MILLIS
    }

    /**
     * A contact with several addresses is recorded once per address, because any of them is an address the user
     * might write to and completion matches on addresses.
     */
    override fun onContactsChanged(contacts: List<GraphContactUpdate>) {
        val changed = contacts.filterNot { it.isRemoved }
            .flatMap { contact -> contact.addresses.map { RemoteContact(it, contact.displayName) } }
        val removed = contacts.filter { it.isRemoved }.flatMap { it.addresses }

        index.recordRemoteContacts(accountUuid, changed)
        index.removeRemoteContacts(accountUuid, removed)
    }
}
