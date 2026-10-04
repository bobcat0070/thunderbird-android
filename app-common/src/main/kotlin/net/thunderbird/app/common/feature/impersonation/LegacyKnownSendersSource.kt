package net.thunderbird.app.common.feature.impersonation

import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.RecipientOrigin
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.impersonation.KnownSenders
import net.thunderbird.feature.impersonation.KnownSendersSource

/**
 * Who the reader knows, from what the device already holds: the recipient index (everyone written to, and the
 * contacts the account's provider lists), the reader's own identities, and the domains of senders whose mail passed
 * DMARC.
 *
 * Verified domains are read from stored mail outside spam and trash. A spammer passing DMARC for their own throwaway
 * domain proves nothing about it, and counting their domain as known would let them borrow a known person's name
 * from it.
 */
internal class LegacyKnownSendersSource(
    private val accountManager: LegacyAccountDtoManager,
    private val recipientIndex: RecipientIndex,
    private val messageStoreManager: MessageStoreManager,
) : KnownSendersSource {

    override fun knownSenders(): KnownSenders {
        val recipients = recipientIndex.all().filter { it.timesUsed > 0 || it.origin == RecipientOrigin.REMOTE }
        val accounts = accountManager.getAccounts()
        val identities = accounts.flatMap { it.identities }
            .mapNotNull { identity -> identity.email?.lowercase()?.let { email -> identity.name to email } }

        val addressesByName = buildMap<String, MutableSet<String>> {
            for (recipient in recipients) {
                val name = recipient.displayName?.takeIf { it.isNotBlank() } ?: continue
                getOrPut(name) { mutableSetOf() }.add(recipient.address)
            }
            for ((name, email) in identities) {
                if (name.isNullOrBlank()) continue
                getOrPut(name) { mutableSetOf() }.add(email)
            }
        }

        val verifiedSenderDomains = accounts.flatMapTo(mutableSetOf()) { account ->
            messageStoreManager.getMessageStore(account).getMessages(
                selection = "messages.sender_authenticated = 1 AND folders.type NOT IN ('spam', 'trash')",
                selectionArgs = emptyArray(),
                sortOrder = "messages.id",
                messageMapper = { message ->
                    message.fromAddresses.firstOrNull()?.address?.substringAfterLast('@', missingDelimiterValue = "")
                        ?.lowercase()?.takeIf { it.isNotEmpty() }
                },
            )
        }

        return KnownSenders(
            addressesByName = addressesByName,
            correspondentAddresses = recipients.mapTo(mutableSetOf()) { it.address } +
                identities.map { (_, email) -> email },
            verifiedSenderDomains = verifiedSenderDomains,
        )
    }
}
