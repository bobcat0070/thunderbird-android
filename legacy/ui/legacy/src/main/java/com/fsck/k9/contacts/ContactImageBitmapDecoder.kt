package com.fsck.k9.contacts

import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.fsck.k9.contacts.bimi.BimiLogoLoader
import com.fsck.k9.contacts.bimi.MarkTrust
import com.fsck.k9.contacts.bimi.withMarkBadge
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import kotlin.math.max

/**
 * [ResourceDecoder] implementation that takes a [ContactImage] and fetches the corresponding contact photo using
 * [ContactPhotoLoader], falls back to [GravatarLoader], and finally generates an image using
 * [ContactLetterBitmapCreator].
 */
internal class ContactImageBitmapDecoder(
    private val contactPhotoLoader: ContactPhotoLoader,
    private val gravatarLoader: GravatarLoader,
    private val bimiLogoLoader: BimiLogoLoader,
    private val websiteIconLoader: WebsiteIconLoader,
    private val bitmapPool: BitmapPool,
) : ResourceDecoder<ContactImage, Bitmap> {

    override fun decode(contactImage: ContactImage, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val size = max(width, height)

        val bitmap = loadContactPhoto(contactImage)
            ?: loadBimiLogo(contactImage, size)
            ?: loadGravatar(contactImage, size)
            ?: loadWebsiteIcon(contactImage)
            ?: createContactLetterBitmap(contactImage, size)

        return BitmapResource.obtain(bitmap, bitmapPool)
    }

    private fun loadContactPhoto(contactImage: ContactImage): Bitmap? {
        if (contactImage.contactLetterOnly) return null

        return contactPhotoLoader.loadContactPhoto(contactImage.address.address)
    }

    /**
     * The sender domain's brand indicator, asked for only when the receiving server reported that the message
     * passed DMARC.
     *
     * That check is the entire basis for showing the logo. Without it the indicator would say nothing about
     * who sent the message while looking exactly like it did, which is worse than showing no logo at all.
     */
    @Suppress("ReturnCount")
    private fun loadBimiLogo(contactImage: ContactImage, size: Int): Bitmap? {
        if (contactImage.contactLetterOnly || !contactImage.isSenderAuthenticated) return null

        val domain = contactImage.address.address?.substringAfterLast('@', "")?.takeIf { it.isNotEmpty() }
            ?: return null

        return bimiLogoLoader.loadLogo(domain, size)
    }

    /**
     * Asked only after the device's own contacts, so a picture the user already has for someone always wins
     * over one fetched from the internet.
     */
    private fun loadGravatar(contactImage: ContactImage, size: Int): Bitmap? {
        if (contactImage.contactLetterOnly) return null

        return gravatarLoader.loadGravatar(contactImage.address.address, size)
    }

    /**
     * The icon of the sender domain's website, asked for last and only when the message passed DMARC.
     *
     * The DMARC gate is the same one the brand indicator is behind and for the same reason: without it a
     * message that merely claims to come from a domain would be shown wearing that domain's icon.
     *
     * Badged as unverified, because that is what it is. Nobody attested to it, and unlike a self-asserted
     * brand indicator the domain did not even publish it as a mail identity - it is only what a browser tab
     * shows for that website. Sharing the badge with the weakest attested tier says the honest thing to the
     * reader, which is that no authority stands behind this picture.
     */
    @Suppress("ReturnCount")
    private fun loadWebsiteIcon(contactImage: ContactImage): Bitmap? {
        if (contactImage.contactLetterOnly || !contactImage.isSenderAuthenticated) return null

        val domain = contactImage.address.address?.substringAfterLast('@', "")?.takeIf { it.isNotEmpty() }
            ?: return null

        return websiteIconLoader.loadIcon(domain)?.withMarkBadge(MarkTrust.SELF_ASSERTED)
    }

    private fun createContactLetterBitmap(contactImage: ContactImage, size: Int): Bitmap {
        val bitmap = bitmapPool.getDirty(size, size, Bitmap.Config.ARGB_8888)
        return contactImage.contactLetterBitmapCreator.drawBitmap(bitmap, size, contactImage.address)
    }

    override fun handles(source: ContactImage, options: Options) = true
}

internal class ContactImageBitmapDecoderFactory(
    private val contactPhotoLoader: ContactPhotoLoader,
    private val gravatarLoader: GravatarLoader,
    private val bimiLogoLoader: BimiLogoLoader,
    private val websiteIconLoader: WebsiteIconLoader,
) {
    fun create(bitmapPool: BitmapPool): ContactImageBitmapDecoder {
        return ContactImageBitmapDecoder(
            contactPhotoLoader,
            gravatarLoader,
            bimiLogoLoader,
            websiteIconLoader,
            bitmapPool,
        )
    }
}
