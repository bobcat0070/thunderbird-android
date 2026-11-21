package net.thunderbird.app.common.feature.plusaddress

import app.k9mail.legacy.mailstore.MessageStoreManager
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.plusaddress.PlusAddressMailSource
import net.thunderbird.feature.plusaddress.PlusAddressMailbox
import net.thunderbird.feature.plusaddress.PlusAddressedMail

/**
 * Only mail with a `+` among its recipients can be on a plus address; the rest of the mailbox is never read.
 */
private const val PLUS_RECIPIENT_SELECTION = "(messages.to_list LIKE '%+%' OR messages.cc_list LIKE '%+%')"

/**
 * Reads the stored mail of every account that was sent to an address with a `+` in it, and the reader's own
 * addresses: every identity of every account, since a plus address handed out from one account's address can be
 * collected by another.
 */
internal class LegacyPlusAddressMailSource(
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
) : PlusAddressMailSource {

    override fun read(): PlusAddressMailbox {
        val accounts = accountManager.findAll()
        val readerAddresses = accounts.flatMapTo(mutableSetOf()) { account ->
            account.identities.mapNotNull { it.email?.lowercase() }
        }

        val mail = accounts.flatMap { account ->
            val spamOrTrash = setOfNotNull(account.spamFolderId, account.trashFolderId)
            messageStoreManager.getMessageStore(account.id).getMessages(
                selection = PLUS_RECIPIENT_SELECTION,
                selectionArgs = emptyArray(),
                sortOrder = "messages.id",
                messageMapper = { message ->
                    PlusAddressedMail(
                        recipientAddresses = (message.toAddresses + message.ccAddresses).mapNotNull { it.address },
                        senderAddress = message.fromAddresses.firstOrNull()?.address,
                        receivedAt = message.internalDate,
                        isSenderAuthenticated = message.isSenderAuthenticated,
                        isInSpamOrTrash = message.folderId in spamOrTrash,
                    )
                },
            )
        }

        return PlusAddressMailbox(readerAddresses = readerAddresses, mail = mail)
    }
}
