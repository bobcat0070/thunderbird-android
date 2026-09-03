package com.fsck.k9.contacts

import com.bumptech.glide.load.Key
import com.fsck.k9.mail.Address
import java.security.MessageDigest

/**
 * Contains all information necessary for [ContactImageBitmapDecoder] to load the contact picture in the desired format.
 */
class ContactImage(
    val contactLetterOnly: Boolean,
    val backgroundCacheId: String,
    val contactLetterBitmapCreator: ContactLetterBitmapCreator,
    val address: Address,
    /**
     * Whether the message this picture belongs to passed DMARC. Part of the cache key, so a spoofed message
     * can never be served the brand logo cached for the domain's real mail.
     */
    val isSenderAuthenticated: Boolean = false,
) : Key {
    private val contactLetterSignature = contactLetterBitmapCreator.signatureOf(address)

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(toString().toByteArray(Key.CHARSET))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContactImage

        if (contactLetterOnly != other.contactLetterOnly) return false
        if (isSenderAuthenticated != other.isSenderAuthenticated) return false
        if (backgroundCacheId != other.backgroundCacheId) return false
        if (address != other.address) return false
        if (contactLetterSignature != other.contactLetterSignature) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contactLetterOnly.hashCode()
        result = 31 * result + isSenderAuthenticated.hashCode()
        result = 31 * result + backgroundCacheId.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + contactLetterSignature.hashCode()
        return result
    }

    override fun toString(): String {
        return "ContactImage(" +
            "contactLetterOnly=$contactLetterOnly, " +
            "backgroundCacheId='$backgroundCacheId', " +
            "address=$address, " +
            "isSenderAuthenticated=$isSenderAuthenticated, " +
            "contactLetterSignature='$contactLetterSignature'" +
            ")"
    }
}
