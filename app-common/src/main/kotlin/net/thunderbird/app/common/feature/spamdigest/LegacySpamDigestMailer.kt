package net.thunderbird.app.common.feature.spamdigest

import android.app.PendingIntent
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.helper.toCrLf
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.message.MessageBuilder
import com.fsck.k9.message.QuotedTextMode
import com.fsck.k9.message.SimpleMessageBuilder
import com.fsck.k9.message.SimpleMessageFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.spamdigest.SpamDigestMailer

/**
 * Sends the digest through the account's outbox, built the way the composer builds a plain-text message so the
 * privacy settings - hidden time zone, hidden user agent - apply to it as to anything else the user sends.
 */
internal class LegacySpamDigestMailer(
    private val accountManager: LegacyAccountDtoManager,
    private val messagingController: MessagingController,
    private val generalSettingsManager: GeneralSettingsManager,
) : SpamDigestMailer {

    override suspend fun sendToSelf(accountId: String, subject: String, body: String) {
        val account = requireNotNull(accountManager.getAccount(accountId)) { "Account not found" }

        // The user's signature is for mail to other people, not a report the app writes to them.
        val identity = account.identities.first().copy(signatureUse = false)
        val self = Address(identity.email ?: account.email, identity.name)

        val message = buildMessage(identity, self, subject, body)
        messagingController.sendMessage(account, message, subject, null)
    }

    private suspend fun buildMessage(identity: Identity, recipient: Address, subject: String, body: String) =
        suspendCancellableCoroutine { continuation ->
            val builder = SimpleMessageBuilder.newInstance()
                .setSubject(subject)
                .setSentDate(Date())
                .setHideTimeZone(generalSettingsManager.getConfig().privacy.isHideTimeZone)
                .setTo(listOf(recipient))
                .setIdentity(identity)
                .setMessageFormat(SimpleMessageFormat.TEXT)
                .setText(body.toCrLf())
                .setAttachments(emptyList())
                .setInlineAttachments(emptyMap())
                .setQuotedTextMode(QuotedTextMode.NONE)

            builder.buildAsync(
                object : MessageBuilder.Callback {
                    override fun onMessageBuildSuccess(message: MimeMessage, isDraft: Boolean) {
                        continuation.resume(message)
                    }

                    override fun onMessageBuildCancel() {
                        continuation.cancel()
                    }

                    override fun onMessageBuildException(exception: MessagingException) {
                        continuation.resumeWithException(exception)
                    }

                    override fun onMessageBuildReturnPendingIntent(pendingIntent: PendingIntent, requestCode: Int) {
                        continuation.resumeWithException(
                            IllegalStateException("Building the spam digest asked for user interaction"),
                        )
                    }
                },
            )
        }
}
