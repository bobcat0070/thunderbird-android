package net.thunderbird.app.common.feature.spamdigest

import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.AuthenticationMethod
import com.fsck.k9.mailstore.AuthenticationServerTrust
import com.fsck.k9.mailstore.KnownContacts
import com.fsck.k9.mailstore.KnownCorrespondents
import com.fsck.k9.mailstore.authenticationOutcomes
import com.fsck.k9.mailstore.hasDmarcFail
import com.fsck.k9.mailstore.recipients.RecipientIndex
import com.fsck.k9.mailstore.recipients.RecipientOrigin
import com.fsck.k9.mailstore.senderDomainOf
import kotlin.time.Instant
import net.thunderbird.feature.impersonation.ImpersonationChecker
import net.thunderbird.feature.spamdigest.SenderCheck
import net.thunderbird.feature.spamdigest.SenderCheckMethod
import net.thunderbird.feature.spamdigest.SpamMessage

/**
 * Describes a message found in spam: what the account's own server said about its sender, whether the sender is
 * someone the reader knows, and whether it impersonates anyone.
 *
 * "Known" means written to at least twice, in the address book, or a contact the account's provider lists - the
 * same people the rest of the app treats as correspondents. It is withheld from anything that failed DMARC or
 * impersonates someone, because a forged message from a known name is exactly what a spam folder is for, and
 * listing it as "possibly not spam" would do the forger's work.
 */
internal class SpamSenderAssessor(
    private val authenticationServerTrust: AuthenticationServerTrust,
    private val knownCorrespondents: KnownCorrespondents,
    private val knownContacts: KnownContacts,
    private val recipientIndex: RecipientIndex,
    private val impersonationChecker: ImpersonationChecker,
) {
    fun assess(
        accountId: String,
        sender: Address?,
        subject: String?,
        receivedAt: Instant,
        authenticationResults: List<String>,
    ): SpamMessage {
        val address = sender?.address
        val domain = senderDomainOf(address)
        val trustedServerId = authenticationServerTrust.trustedServerId(accountId)

        val senderChecks = authenticationOutcomes(authenticationResults, domain, trustedServerId)
            .map { outcome -> SenderCheck(outcome.method.toSenderCheckMethod(), outcome.passed) }
        val impersonation = impersonationChecker.check(sender?.personal, address)
        val isForged = hasDmarcFail(authenticationResults, domain, trustedServerId) || impersonation != null

        return SpamMessage(
            senderName = sender?.personal,
            senderAddress = address,
            subject = subject,
            receivedAt = receivedAt,
            senderChecks = senderChecks,
            isFromKnownSender = address != null && !isForged && isKnown(address),
            impersonation = impersonation,
        )
    }

    private fun isKnown(address: String): Boolean {
        return knownCorrespondents.isKnown(address) ||
            recipientIndex.recipient(address)?.origin == RecipientOrigin.REMOTE ||
            knownContacts.isKnown(address)
    }

    private fun AuthenticationMethod.toSenderCheckMethod(): SenderCheckMethod = when (this) {
        AuthenticationMethod.SPF -> SenderCheckMethod.SPF
        AuthenticationMethod.DKIM -> SenderCheckMethod.DKIM
        AuthenticationMethod.DMARC -> SenderCheckMethod.DMARC
    }
}
