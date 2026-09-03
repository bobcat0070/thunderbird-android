package com.fsck.k9.contacts.bimi

import java.util.zip.GZIPInputStream

/**
 * RFC 3709 logotype extension, where a Verified Mark Certificate carries the mark the authority verified.
 */
const val LOGOTYPE_EXTENSION_OID = "1.3.6.1.5.5.7.1.12"

private const val TAG_IA5_STRING = 0x16
private const val TAG_OCTET_STRING = 0x04
private const val TAG_CONSTRUCTED = 0x20

private const val DATA_URI_PREFIX = "data:image/svg+xml;base64,"

/**
 * gzip's magic number. Marks embedded in a certificate are usually compressed, which the data URI does not
 * say, so it has to be detected.
 */
private const val GZIP_MAGIC_FIRST = 0x1F
private const val GZIP_MAGIC_SECOND = 0x8B.toByte().toInt() and 0xFF

private const val BYTE_MASK = 0xFF
private const val LENGTH_LONG_FORM = 0x80
private const val MAX_NESTING_DEPTH = 16

/**
 * A length encoded in more than four bytes is not something a certificate extension needs.
 */
private const val MAX_LENGTH_BYTES = 4
private const val BITS_PER_BYTE = 8

/**
 * Extracts the mark embedded in a certificate's logotype extension.
 *
 * The extension is walked rather than decoded against the full RFC 3709 grammar: everything needed is the one
 * `data:` URI, and a structural walk that collects IA5 strings tolerates the layout differences between
 * issuers without pretending to be a general ASN.1 decoder.
 *
 * The bytes are inside a CA-signed certificate, so their content is attested. They are still parsed
 * defensively, because a malformed certificate must fail rather than throw.
 *
 * @return the SVG, decompressed if it was gzipped, or `null` when the extension carries no usable mark.
 */
@Suppress("ReturnCount")
fun extractLogotypeSvg(extensionValue: ByteArray): ByteArray? {
    val uri = runCatching { collectTaggedStrings(extensionValue, TAG_IA5_STRING) }
        .getOrDefault(emptyList())
        .firstOrNull { it.startsWith(DATA_URI_PREFIX, ignoreCase = true) }
        ?: return null

    val encoded = uri.substring(DATA_URI_PREFIX.length)
    val decoded = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }
        .getOrNull()
        ?: return null

    return if (decoded.isGzipped()) decoded.gunzipOrNull() else decoded
}

private fun ByteArray.isGzipped(): Boolean =
    size > 1 && (this[0].toInt() and BYTE_MASK) == GZIP_MAGIC_FIRST &&
        (this[1].toInt() and BYTE_MASK) == GZIP_MAGIC_SECOND

private fun ByteArray.gunzipOrNull(): ByteArray? =
    runCatching { GZIPInputStream(inputStream()).use { it.readBytes() } }.getOrNull()

/**
 * Walks the DER structure, descending into constructed values and collecting every string with [targetTag].
 *
 * Shared by the two extensions read here: a logotype carries its mark as an IA5 string, and a distribution
 * point carries its URL under a context-specific tag. Both want the same walk over the same shape.
 */
internal fun collectTaggedStrings(bytes: ByteArray, targetTag: Int, depth: Int = 0): List<String> {
    if (depth > MAX_NESTING_DEPTH) return emptyList()

    return buildList {
        var offset = 0

        while (offset < bytes.size) {
            val tag = bytes[offset].toInt() and BYTE_MASK
            offset++

            val (length, lengthBytes) = readLength(bytes, offset) ?: return@buildList
            offset += lengthBytes
            if (length < 0 || offset + length > bytes.size) return@buildList

            when {
                tag == targetTag -> add(String(bytes, offset, length, Charsets.US_ASCII))

                tag and TAG_CONSTRUCTED != 0 ->
                    addAll(collectTaggedStrings(bytes.copyOfRange(offset, offset + length), targetTag, depth + 1))
            }

            offset += length
        }
    }
}

/**
 * @return the value's length and how many bytes encoded it, or `null` when the encoding is unusable.
 */
@Suppress("ReturnCount")
private fun readLength(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
    if (offset >= bytes.size) return null

    val first = bytes[offset].toInt() and BYTE_MASK
    if (first and LENGTH_LONG_FORM == 0) return first to 1

    val count = first and (LENGTH_LONG_FORM - 1)
    // An indefinite length, or one too long to be a certificate extension, is not something to guess at.
    if (count == 0 || count > MAX_LENGTH_BYTES || offset + count >= bytes.size) return null

    var length = 0
    for (index in 1..count) {
        length = (length shl BITS_PER_BYTE) or (bytes[offset + index].toInt() and BYTE_MASK)
    }

    return if (length < 0) null else length to (count + 1)
}

/**
 * Extension values arrive wrapped in a DER OCTET STRING, which has to come off before the contents can be
 * read.
 */
internal fun unwrapOctetString(value: ByteArray): ByteArray? {
    if (value.size < 2 || value[0].toInt() != TAG_OCTET_STRING) return null

    val first = value[1].toInt() and BYTE_MASK
    val start = if (first and LENGTH_LONG_FORM == 0) 2 else 2 + (first and (LENGTH_LONG_FORM - 1))

    return if (start >= value.size) null else value.copyOfRange(start, value.size)
}
