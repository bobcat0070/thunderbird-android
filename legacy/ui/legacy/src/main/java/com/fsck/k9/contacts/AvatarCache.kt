package com.fsck.k9.contacts

import android.content.Context
import java.io.File
import java.security.MessageDigest

private const val CACHE_DIRECTORY = "avatar-cache"

/**
 * A miss is recorded as an empty file, so "we asked and there was nothing" is remembered as firmly as a hit.
 */
private const val MISS_MARKER_LENGTH = 0

/**
 * Caches what was fetched for a sender's picture, across app restarts.
 *
 * The in-memory caches this replaces meant every restart re-asked Gravatar and DNS about every sender in the
 * list. That is slow for the user and rude to services that answer for free, and it made a mailbox scroll
 * roughly as expensive on the hundredth launch as on the first.
 *
 * Misses are cached too, and matter more than hits: most senders have no picture anywhere, so without
 * remembering the absence the common case is exactly the one that repeats.
 */
class AvatarCache(
    context: Context,
    private val hitLifetimeMillis: Long = DEFAULT_HIT_LIFETIME_MILLIS,
    private val missLifetimeMillis: Long = DEFAULT_MISS_LIFETIME_MILLIS,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val directory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)

    /**
     * @return the cached bytes, an empty array for a remembered miss, or `null` when nothing usable is held.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun get(key: String): ByteArray? {
        return try {
            val file = fileFor(key)
            if (!file.exists()) return null

            val age = currentTimeMillis() - file.lastModified()
            val lifetime = if (file.length() == MISS_MARKER_LENGTH.toLong()) {
                missLifetimeMillis
            } else {
                hitLifetimeMillis
            }

            if (age > lifetime) {
                file.delete()
                null
            } else {
                file.readBytes()
            }
        } catch (e: Exception) {
            // A cache that cannot be read is a cache miss, never an error worth surfacing.
            null
        }
    }

    fun put(key: String, bytes: ByteArray) {
        write(key, bytes)
    }

    /**
     * Records that this key has nothing behind it.
     */
    fun putMiss(key: String) {
        write(key, ByteArray(0))
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun write(key: String, bytes: ByteArray) {
        try {
            directory.mkdirs()
            // Written beside and moved into place, so a kill part-way through leaves no half-written entry
            // that would later be read back as a valid picture.
            val temporary = File(directory, "${keyHash(key)}.tmp")
            temporary.writeBytes(bytes)
            temporary.renameTo(fileFor(key))
            // Stamped rather than left to the filesystem, so age is measured against the same clock the
            // reader uses and an entry cannot look fresh because a device's time moved.
            fileFor(key).setLastModified(currentTimeMillis())
        } catch (e: Exception) {
            // Failing to cache costs a repeated fetch and nothing else.
        }
    }

    private fun fileFor(key: String) = File(directory, keyHash(key))

    /**
     * Hashed because keys are email addresses and domains: this keeps them off the filesystem in the clear,
     * and keeps every file name valid whatever the address contains.
     */
    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))

        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /**
         * Pictures change rarely, so a week of reuse is a good trade against re-asking constantly.
         */
        const val DEFAULT_HIT_LIFETIME_MILLIS = 7L * DAY_MILLIS

        /**
         * Absences are re-checked sooner, because someone signing up for Gravatar or publishing a mark should
         * show up without waiting a week.
         */
        const val DEFAULT_MISS_LIFETIME_MILLIS = DAY_MILLIS
    }
}
