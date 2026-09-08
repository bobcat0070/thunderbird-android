package com.fsck.k9.ui.messageview

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import net.thunderbird.core.android.account.LegacyAccountDto

/**
 * Set by a delivering server to record the address it delivered to, which is the whole answer when there is one.
 */
private val DELIVERY_HEADERS = listOf("Delivered-To", "X-Original-To")

/**
 * Works out which of the reader's addresses a message arrived on.
 *
 * Mail reaches one mailbox by several routes - a plus-addressed variant handed to one shop, an alias on another
 * domain, a forward from somewhere else - and the message view has never said which. That matters most for
 * exactly the mail people want to trace: a plus address appearing in a sender's list, or an alias still being
 * used after it was retired.
 *
 * The To header alone cannot answer it. For anything forwarded or blind-copied it names whoever the sender
 * wrote to, who is frequently somebody else.
 */
internal object DeliveryAddressExtractor {

    /**
     * @return the address the message was delivered to, or `null` when it cannot be told or is simply the
     *   reader's usual address, which is not worth saying.
     */
    fun extractDeliveryAddress(message: Message, account: LegacyAccountDto): String? {
        val delivered = deliveredHeaderAddress(message) ?: recipientBelongingToReader(message, account)

        return delivered?.takeUnless { account.isAnIdentity(Address(it)) }
    }

    /**
     * The delivering server's own record, which is the only reliable source for a forward or a blind copy.
     */
    private fun deliveredHeaderAddress(message: Message): String? {
        return DELIVERY_HEADERS
            .asSequence()
            .flatMap { name -> message.getHeader(name).orEmpty().asSequence() }
            .mapNotNull { value -> Address.parse(value).firstOrNull()?.address }
            .firstOrNull { it.isNotBlank() }
    }

    /**
     * A recipient that looks like it belongs to the reader, for the common case no header covers.
     *
     * An address at a domain the reader already has an identity on is theirs even when the local part differs,
     * which is what a plus-addressed variant is. Recipients at other domains are left alone: without a header
     * saying so there is no way to tell the reader's own alias from a stranger who was written to as well.
     */
    private fun recipientBelongingToReader(message: Message, account: LegacyAccountDto): String? {
        val identityDomains = account.identities
            .mapNotNull { identity -> identity.email?.domainOrNull() }
            .toSet()

        if (identityDomains.isEmpty()) return null

        return sequenceOf(Message.RecipientType.TO, Message.RecipientType.CC)
            .flatMap { type -> message.getRecipients(type).orEmpty().asSequence() }
            .mapNotNull { address -> address.address }
            .firstOrNull { address -> address.domainOrNull() in identityDomains }
    }

    private fun String.domainOrNull(): String? =
        substringAfterLast('@', missingDelimiterValue = "").lowercase().takeIf { it.isNotEmpty() }
}
