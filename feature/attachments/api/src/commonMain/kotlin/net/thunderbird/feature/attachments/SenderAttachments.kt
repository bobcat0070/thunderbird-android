package net.thunderbird.feature.attachments

/**
 * A file that came with a message, as stored on the device.
 *
 * @param key what the app needs to find the file and its message again; opaque to the screen.
 * @param size the file's size in bytes, or `null` when the message does not say.
 * @param date when the message was sent.
 * @param isDownloaded whether the file itself is on the device; one that is not is downloaded when opened.
 */
data class StoredAttachment(
    val key: String,
    val fileName: String,
    val mimeType: String?,
    val size: Long?,
    val date: Long,
    val subject: String?,
    val isDownloaded: Boolean,
)

/**
 * @param attachments the files, newest message first.
 * @param messagesNotDownloaded how many of the sender's messages are known only by their envelope, as Microsoft 365
 *   mail is until it is opened, so whether they hold files cannot be told.
 */
data class SenderAttachments(
    val attachments: List<StoredAttachment>,
    val messagesNotDownloaded: Int,
)

/**
 * What happened when a file was asked to be opened.
 */
enum class OpenOutcome {
    /** An app was opened to show the file. */
    OPENED,

    /** No installed app can show a file of this type. */
    NO_VIEWER,

    /** The file could not be downloaded or read. */
    FAILED,
}

/**
 * Reads, opens and downloads the files someone sent the reader, across every account. Mail in the trash and in spam
 * is left out, as in the list of all mail from a sender.
 */
interface SenderAttachmentRepository {
    suspend fun attachments(address: String): SenderAttachments

    /**
     * Downloads the newest [limit] of the sender's messages that are known only by their envelope, so their files
     * can be listed.
     *
     * @param onProgress called after each message, with how many are done and how many there are.
     * @return how many messages were downloaded.
     */
    suspend fun downloadMessages(address: String, limit: Int, onProgress: (done: Int, total: Int) -> Unit): Int

    /**
     * Opens [attachment] in an app that can show it, downloading it first when it is not on the device.
     */
    suspend fun open(attachment: StoredAttachment): OpenOutcome

    /**
     * Opens the message [attachment] came with.
     */
    fun showMessage(attachment: StoredAttachment)
}
