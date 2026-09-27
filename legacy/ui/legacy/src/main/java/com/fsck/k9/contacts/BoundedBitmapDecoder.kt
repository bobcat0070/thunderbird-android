package com.fsck.k9.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Larger than any picture service sends: an image claiming more than this is refused outright.
 */
private const val MAX_SOURCE_DIMENSION = 4096

/**
 * The size pictures are decoded down to. Far above the 40dp a sender picture is drawn at, so nothing visible is lost.
 */
private const val MAX_DECODED_DIMENSION = 512

/**
 * Decodes a downloaded picture, checking its size before any memory is spent on it.
 *
 * A few hundred kilobytes of PNG can declare a bitmap of gigabytes, and decoding it in one go would take the app
 * down. The dimensions are read first; an absurd image is refused and a large one decoded at a fraction of its size.
 *
 * @return the bitmap, or `null` when the bytes are not an image or claim to be an impossibly large one.
 */
internal fun decodeBoundedBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || largestSide > MAX_SOURCE_DIMENSION) return null

    var sampleSize = 1
    while (largestSide / (sampleSize * 2) >= MAX_DECODED_DIMENSION) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
