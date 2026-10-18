package net.thunderbird.app.common.feature.impersonation

import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.RecipientOrigin
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.impersonation.KnownSenders
import net.thunderbird.feature.impersonation.KnownSendersSource

/**
 * Who the reader knows, from what the device already holds: the recipient index (everyone written to, and the
 * contacts the account's provider lists), the reader's own identities, and the domains of senders whose mail passed
 * DMARC.
 *
 * The recipient index is read each time, so someone just written to is known at once. The verified domains are the
 * ones the nightly scan saved; see [VerifiedSenderDomainScanner] for which mail counts.
 */
internal class LegacyKnownSendersSource(
    private val accountManager: LegacyAccountManager,
    private val recipientIndex: RecipientIndex,
    private val verifiedSenderDomainStore: VerifiedSenderDomainStore,
) : KnownSendersSource {

    override fun knownSenders(): KnownSenders {
        val recipients = recipientIndex.all().filter { it.timesUsed > 0 || it.origin == RecipientOrigin.REMOTE }
        val accounts = accountManager.findAll()
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

        val verifiedSenderDomains = verifiedSenderDomainStore.domains().orEmpty()

        return KnownSenders(
            addressesByName = addressesByName,
            correspondentAddresses = recipients.mapTo(mutableSetOf()) { it.address } +
                identities.map { (_, email) -> email },
            verifiedSenderDomains = verifiedSenderDomains,
        )
    }
}
