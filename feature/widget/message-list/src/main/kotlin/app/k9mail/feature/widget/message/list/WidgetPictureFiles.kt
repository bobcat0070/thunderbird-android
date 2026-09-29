package app.k9mail.feature.widget.message.list

import java.io.File
import java.security.MessageDigest

/**
 * Stores [picture], a PNG, in [directory] under a name taken from a hash of its content, and returns that file.
 *
 * The same picture always gets the same file, so the widget row keeps the same link and the home screen does not
 * load it again; a changed picture gets a new file. The name carries nothing about the sender. The file's time is set
 * to [now] whether it was written or already there, which is what [removePicturesOlderThan] goes by.
 */
internal fun storePicture(directory: File, picture: ByteArray, now: Long): File {
    directory.mkdirs()

    val file = File(directory, "${sha256(picture)}.png")
    if (!file.exists()) {
        val partialFile = File(directory, "${file.name}.partial")
        partialFile.writeBytes(picture)
        partialFile.renameTo(file)
    }
    file.setLastModified(now)

    return file
}

/**
 * Deletes the pictures in [directory] not stored or reused since [cutoff]: those of messages no longer in the widget.
 */
internal fun removePicturesOlderThan(directory: File, cutoff: Long) {
    directory.listFiles()
        ?.filter { file -> file.isFile && file.lastModified() < cutoff }
        ?.forEach { file -> file.delete() }
}

private fun sha256(data: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
