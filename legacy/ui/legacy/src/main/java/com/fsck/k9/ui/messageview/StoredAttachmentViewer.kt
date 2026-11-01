package com.fsck.k9.ui.messageview

import android.content.Context
import android.content.Intent
import androidx.annotation.WorkerThread
import com.fsck.k9.helper.MimeTypeUtil
import com.fsck.k9.mailstore.AttachmentViewInfo
import com.fsck.k9.provider.AttachmentTempFileProvider
import java.io.IOException

/**
 * Makes the intent that opens a stored attachment in another app, as the message view does, for screens that list
 * attachments outside a message. The attachment must already be on the device.
 */
class StoredAttachmentViewer(private val context: Context) {
    /**
     * @return the intent, ready to start from any context, or `null` when the attachment could not be read.
     */
    @WorkerThread
    fun createViewIntent(attachment: AttachmentViewInfo): Intent? {
        return try {
            val contentUri = AttachmentTempFileProvider.createTempUriForContentUri(
                context,
                attachment.internalUri,
                attachment.displayName,
            )
            val mimeType = attachment.mimeType ?: MimeTypeUtil.DEFAULT_ATTACHMENT_MIME_TYPE
            ViewIntentFinder(context).getBestViewIntent(contentUri, attachment.displayName, mimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: IOException) {
            null
        }
    }
}
