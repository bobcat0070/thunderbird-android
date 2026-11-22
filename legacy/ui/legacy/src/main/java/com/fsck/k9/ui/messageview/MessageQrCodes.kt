package com.fsck.k9.ui.messageview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MimeUtility
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.InputStream

/**
 * The most pictures of one message that are read for a QR code. Mail that hides a link in a code sends one; a
 * newsletter full of product photos should not take long to open.
 */
private const val MAX_IMAGES = 8

/**
 * Pictures larger than this are not read: a QR code that should be scanned off a screen is never that big.
 */
private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

/**
 * The longest side a picture is scaled down to before it is read, which is plenty for a code and keeps memory low.
 */
private const val MAX_SIDE = 1600

private val WEB_LINK = Regex("""(?i)^https?://\S+$""")

/**
 * Finds a web link hidden in a QR code in a message's pictures - those attached or shown in its text, never one that
 * would have to be fetched from the sender's server.
 *
 * A link in a picture slips past anything that checks a message's links, which is why phishing mail uses them.
 */
internal object MessageQrCodes {

    /**
     * @return the first web link a QR code in [message] holds, or `null`.
     */
    @JvmStatic
    fun findQrLink(message: Part): String? {
        return imageParts(message).take(MAX_IMAGES).firstNotNullOfOrNull { part -> readQrCode(part) }
            ?.trim()
            ?.takeIf { WEB_LINK.matches(it) }
    }

    private fun imageParts(part: Part): Sequence<Part> = sequence {
        val body = part.body
        if (body is Multipart) {
            for (child in body.bodyParts) yieldAll(imageParts(child))
        } else if (part.mimeType?.lowercase()?.startsWith("image/") == true && body != null) {
            yield(part)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    private fun readQrCode(part: Part): String? {
        return try {
            val bytes = MimeUtility.decodeBody(part.body)?.use { it.readAtMost(MAX_IMAGE_BYTES) } ?: return null
            val bitmap = decodeScaled(bytes) ?: return null
            try {
                decodeQr(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            // A picture that is not on the device, or not a picture at all, holds no code worth reporting.
            null
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SIDE) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    // No code in the picture is the usual outcome, not an error.
    @Suppress("SwallowedException")
    private fun decodeQr(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))

        return try {
            QRCodeReader().decode(image, mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: ReaderException) {
            null
        }
    }

    /**
     * @return the stream's bytes, or `null` when there are more than [limit].
     */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            output.write(buffer, 0, read)
            if (output.size() > limit) return null
        }
    }

    private const val BUFFER_SIZE = 16 * 1024
}
