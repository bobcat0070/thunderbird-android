package net.thunderbird.feature.blocklist.internal

private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val BYTE_MASK = 0xff

private const val MAGIC = 0x5442444c // "TBDL"
private const val FORMAT_VERSION = 1
private const val HEADER_BYTES = 12
private const val LONG_BYTES = 8
private const val INT_BYTES = 4
private const val BITS_PER_BYTE = 8

/**
 * The most entries a list is trusted to have. HaGeZi's largest list on offer has under 600,000; anything far past
 * that is not one of its lists and is not held in memory.
 */
internal const val MAX_LIST_ENTRIES = 1_500_000

/**
 * A blocklist kept as the sorted 64-bit hashes of its domains rather than the domains themselves: an eighth of the
 * memory, a lookup that is a binary search, and a file that loads without parsing text. Two domains sharing a hash
 * is about one chance in a hundred million million for a list this size, and would only show a note beside a link.
 */
internal class HashedDomainList private constructor(private val hashes: LongArray) {
    val size: Int get() = hashes.size

    operator fun contains(domain: String): Boolean = hashes.binarySearch(domainHash(domain)) >= 0

    fun encode(): ByteArray {
        val bytes = ByteArray(HEADER_BYTES + hashes.size * LONG_BYTES)
        bytes.putInt(0, MAGIC)
        bytes.putInt(INT_BYTES, FORMAT_VERSION)
        bytes.putInt(INT_BYTES * 2, hashes.size)
        hashes.forEachIndexed { index, hash -> bytes.putLong(HEADER_BYTES + index * LONG_BYTES, hash) }
        return bytes
    }

    companion object {
        /**
         * @param lines a list in HaGeZi's wildcard-domains format: one domain per line, each covering its
         *   subdomains; `#` starts a comment.
         * @return the list, or `null` when it holds no domains or more than [MAX_LIST_ENTRIES].
         */
        fun fromLines(lines: Sequence<String>): HashedDomainList? {
            val hashes = LongArrayBuilder()
            for (line in lines) {
                val domain = line.trim()
                if (domain.isEmpty() || domain.startsWith("#")) continue
                if (hashes.size >= MAX_LIST_ENTRIES) return null
                hashes.add(domainHash(domain))
            }
            if (hashes.size == 0) return null

            val sorted = hashes.toArray().apply { sort() }
            return HashedDomainList(sorted.distinctSorted())
        }

        /**
         * @return the list [encode] wrote, or `null` for anything else - a truncated file, another version.
         */
        @Suppress("ReturnCount")
        fun decode(bytes: ByteArray): HashedDomainList? {
            if (bytes.size < HEADER_BYTES) return null
            if (bytes.getInt(0) != MAGIC || bytes.getInt(INT_BYTES) != FORMAT_VERSION) return null

            val count = bytes.getInt(INT_BYTES * 2)
            if (count !in 1..MAX_LIST_ENTRIES || bytes.size != HEADER_BYTES + count * LONG_BYTES) return null

            val hashes = LongArray(count) { index -> bytes.getLong(HEADER_BYTES + index * LONG_BYTES) }
            for (index in 1 until count) {
                if (hashes[index] <= hashes[index - 1]) return null
            }
            return HashedDomainList(hashes)
        }
    }
}

/**
 * FNV-1a over the domain's lowercase ASCII form. Domains in the lists, and hosts after `IDN.toASCII`, are ASCII.
 */
internal fun domainHash(domain: String): Long {
    var hash = FNV_OFFSET_BASIS
    for (character in domain.trim().trimEnd('.').lowercase()) {
        hash = (hash xor (character.code.toLong() and BYTE_MASK.toLong())) * FNV_PRIME
    }
    return hash
}

private fun LongArray.distinctSorted(): LongArray {
    if (isEmpty()) return this
    var write = 1
    for (read in 1 until size) {
        if (this[read] != this[write - 1]) this[write++] = this[read]
    }
    return copyOf(write)
}

private class LongArrayBuilder {
    private var array = LongArray(INITIAL_CAPACITY)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }

    fun toArray(): LongArray = array.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}

private fun ByteArray.putInt(offset: Int, value: Int) {
    for (index in 0 until INT_BYTES) {
        this[offset + index] = (value shr (BITS_PER_BYTE * (INT_BYTES - 1 - index))).toByte()
    }
}

private fun ByteArray.getInt(offset: Int): Int {
    var value = 0
    for (index in 0 until INT_BYTES) value = (value shl BITS_PER_BYTE) or (this[offset + index].toInt() and BYTE_MASK)
    return value
}

private fun ByteArray.putLong(offset: Int, value: Long) {
    for (index in 0 until LONG_BYTES) {
        this[offset + index] = (value shr (BITS_PER_BYTE * (LONG_BYTES - 1 - index))).toByte()
    }
}

private fun ByteArray.getLong(offset: Int): Long {
    var value = 0L
    for (index in 0 until LONG_BYTES) {
        value = (value shl BITS_PER_BYTE) or (this[offset + index].toLong() and BYTE_MASK.toLong())
    }
    return value
}
