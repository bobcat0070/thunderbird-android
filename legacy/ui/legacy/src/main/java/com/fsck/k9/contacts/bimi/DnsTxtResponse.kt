package com.fsck.k9.contacts.bimi

/**
 * DNS resource record type for TXT.
 */
internal const val DNS_TYPE_TXT = 16

private const val HEADER_LENGTH = 12
private const val QUESTION_COUNT_OFFSET = 4
private const val ANSWER_COUNT_OFFSET = 6

/**
 * The two high bits of a length byte mark a compression pointer rather than a label length.
 */
private const val COMPRESSION_POINTER_MASK = 0xC0
private const val COMPRESSION_POINTER_LENGTH = 2

private const val TYPE_AND_CLASS_LENGTH = 4

/**
 * Masks a signed byte back to the unsigned value the wire format uses.
 */
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val TTL_LENGTH = 4
private const val RDLENGTH_LENGTH = 2

/**
 * Parses the TXT strings out of a raw DNS response.
 *
 * Written by hand because the platform resolver hands back the wire format and there is no public API to
 * decode it. Only what BIMI needs is implemented: answers are walked, TXT records are collected, everything
 * else is skipped.
 *
 * Every read is bounds-checked and a malformed response yields an empty list rather than an exception. The
 * bytes come from the network, so the parser has to treat a truncated or hostile response as ordinary input.
 *
 * @return one string per TXT record, with that record's character-strings concatenated as the DNS
 *   specification requires.
 */
internal fun parseTxtRecords(response: ByteArray): List<String> {
    return runCatching { readTxtRecords(response) }.getOrDefault(emptyList())
}

private fun readTxtRecords(response: ByteArray): List<String> {
    if (response.size < HEADER_LENGTH) return emptyList()

    val questionCount = response.readUShort(QUESTION_COUNT_OFFSET)
    val answerCount = response.readUShort(ANSWER_COUNT_OFFSET)

    var offset = HEADER_LENGTH
    repeat(questionCount) {
        offset = response.skipName(offset) + TYPE_AND_CLASS_LENGTH
    }

    return buildList {
        repeat(answerCount) {
            offset = response.skipName(offset)
            if (offset + TYPE_AND_CLASS_LENGTH + TTL_LENGTH + RDLENGTH_LENGTH > response.size) return@buildList

            val type = response.readUShort(offset)
            offset += TYPE_AND_CLASS_LENGTH + TTL_LENGTH
            val dataLength = response.readUShort(offset)
            offset += RDLENGTH_LENGTH

            if (offset + dataLength > response.size) return@buildList

            if (type == DNS_TYPE_TXT) {
                add(response.readCharacterStrings(offset, dataLength))
            }

            offset += dataLength
        }
    }
}

/**
 * A TXT record is a sequence of length-prefixed strings, and a record longer than 255 bytes is split across
 * several. They are joined without a separator, which is how a long BIMI record survives being chunked.
 */
private fun ByteArray.readCharacterStrings(start: Int, dataLength: Int): String {
    val end = start + dataLength
    var offset = start

    return buildString {
        while (offset < end) {
            val length = this@readCharacterStrings[offset].toInt() and BYTE_MASK
            offset++
            if (offset + length > end) break

            append(String(this@readCharacterStrings, offset, length, Charsets.UTF_8))
            offset += length
        }
    }
}

/**
 * @return the offset just past the name at [start], following the compression pointer if there is one.
 */
@Suppress("ReturnCount")
private fun ByteArray.skipName(start: Int): Int {
    var offset = start

    while (offset < size) {
        val length = this[offset].toInt() and BYTE_MASK

        when {
            // A pointer is always the last thing in a name, so the name ends right after it.
            length and COMPRESSION_POINTER_MASK == COMPRESSION_POINTER_MASK ->
                return offset + COMPRESSION_POINTER_LENGTH

            length == 0 -> return offset + 1

            else -> offset += length + 1
        }
    }

    return offset
}

private fun ByteArray.readUShort(offset: Int): Int {
    if (offset + 1 >= size) return 0

    return ((this[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (this[offset + 1].toInt() and BYTE_MASK)
}
