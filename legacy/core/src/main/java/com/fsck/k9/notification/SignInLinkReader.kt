package com.fsck.k9.notification

import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.message.extractors.findSignInLink
import com.fsck.k9.message.extractors.looksLikeSignInMail
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag

/**
 * Finds the sign-in link in a message a notification is being made for.
 */
internal fun interface SignInLinkReader {
    fun read(message: LocalMessage): String?
}

/**
 * Reads the link from the message's stored body. The body is only loaded for a message whose subject is about
 * signing in, and only when it has been downloaded; nothing is fetched from the server here.
 */
internal class StoredBodySignInLinkReader : SignInLinkReader {
    override fun read(message: LocalMessage): String? {
        val isDownloaded = message.isSet(Flag.X_DOWNLOADED_FULL) || message.isSet(Flag.X_DOWNLOADED_PARTIAL)
        if (!isDownloaded || !looksLikeSignInMail(message.subject)) return null

        return try {
            val fetchProfile = FetchProfile().apply { add(FetchProfile.Item.BODY) }
            message.folder.fetch(listOf(message), fetchProfile, null)
            findSignInLink(message, message.subject)
        } catch (_: MessagingException) {
            null
        }
    }
}
