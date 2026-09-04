package com.fsck.k9.mailstore

import app.k9mail.core.android.common.contact.ContactRepository
import net.thunderbird.core.common.mail.toEmailAddressOrNull

/**
 * Whether a sender is in the user's address book.
 *
 * A thin wrapper, but it exists so the two places that classify mail - the save path and the pass over stored
 * mail - ask the question the same way, including what happens when the address book cannot be reached.
 */
class KnownContacts(private val contactRepository: ContactRepository) {

    /**
     * Lookups go through the address book, which can be unavailable or permission-gated. A failure only means
     * one fewer signal, never a failed save.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun isKnown(emailAddress: String): Boolean {
        return try {
            emailAddress.toEmailAddressOrNull()?.let { contactRepository.hasContactFor(it) } == true
        } catch (e: Exception) {
            false
        }
    }
}
