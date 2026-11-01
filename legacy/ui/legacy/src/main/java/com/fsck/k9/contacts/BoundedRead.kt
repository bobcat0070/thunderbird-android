package com.fsck.k9.contacts

import java.io.InputStream

/**
 * @return the stream's bytes, or `null` when it holds more than [limit], so a host that ignores its own
 *   declared content length, or declares none, still cannot stream without end.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray? {
    val buffer = ByteArray(limit + 1)
    var read = 0

    while (read < buffer.size) {
        val count = read(buffer, read, buffer.size - read)
        if (count == -1) break
        read += count
    }

    return if (read > limit) null else buffer.copyOf(read)
}
