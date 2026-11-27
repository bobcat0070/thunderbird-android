package com.fsck.k9.ui.messageview

import android.content.Context
import com.fsck.k9.mail.Message
import com.fsck.k9.mailstore.hasDmarcFail
import com.fsck.k9.mailstore.senderDomainOf
import com.fsck.k9.message.extractors.AttachmentRisk
import com.fsck.k9.message.extractors.RiskyAttachment
import com.fsck.k9.message.extractors.SecurityAlert
import com.fsck.k9.message.extractors.attachmentRisk
import com.fsck.k9.message.extractors.findSecurityAlert
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Headers only a discussion list puts on a message: a list about passwords says nothing about the reader's account.
 */
private val LIST_HEADERS = listOf("List-Id", "List-Post")

/**
 * The message view's notes about a message's security: what a service's security alert says happened, a data breach
 * notice, and attachments of a kind phishing and malware mail sends.
 */
internal object SecurityNotes {

    /**
     * A line under the sender.
     *
     * @param isWarning whether it is shown as a warning, rather than as advice.
     */
    class Note(@JvmField val text: String, @JvmField val isWarning: Boolean)

    /**
     * Says what a service's security alert is about and what to do about it: go to the site rather than through the
     * mail, which is how phishing dressed as such an alert gets its sign-ins. An alert that failed its sender's
     * check is said to be one only in a warning.
     *
     * @return the note, or `null` when the message is no security alert.
     */
    @JvmStatic
    @Suppress("ReturnCount")
    fun securityAlertNote(
        context: Context,
        message: Message,
        senderAddress: String?,
        authenticationResults: List<String>,
        trustedServerId: String?,
    ): Note? {
        if (LIST_HEADERS.any { message.getHeader(it).isNotEmpty() }) return null
        val alert = findSecurityAlert(message.subject) ?: return null
        val domain = senderDomainOf(senderAddress) ?: return null

        if (hasDmarcFail(authenticationResults, domain, trustedServerId)) {
            return Note(context.getString(R.string.message_view_security_alert_failed, domain), isWarning = true)
        }
        val text = when (alert) {
            SecurityAlert.SIGN_IN -> R.string.message_view_security_alert_sign_in
            SecurityAlert.PASSWORD_RESET -> R.string.message_view_security_alert_password_reset
            SecurityAlert.PASSWORD_CHANGED -> R.string.message_view_security_alert_password_changed
            SecurityAlert.SECURITY_SETTINGS -> R.string.message_view_security_alert_security_settings
            SecurityAlert.OTHER -> R.string.message_view_security_alert_other
        }
        return Note(context.getString(text, domain), isWarning = false)
    }

    /**
     * @param plusAddresses the reader's plus addresses the sender has; the first is named.
     */
    @JvmStatic
    fun breachNoticeText(context: Context, senderAddress: String, plusAddresses: List<String>): String? {
        val domain = senderDomainOf(senderAddress) ?: return null
        val plusAddress = plusAddresses.firstOrNull()
        return if (plusAddress == null) {
            context.getString(R.string.message_view_breach_notice, domain)
        } else {
            context.getString(R.string.message_view_breach_notice_plus_address, domain, plusAddress)
        }
    }

    /**
     * Whether a breach notice may be pointed out: not when it failed its sender's check, as a forged notice asking
     * the reader to "reset their password here" would.
     */
    @JvmStatic
    fun mayShowBreachNotice(
        senderAddress: String?,
        authenticationResults: List<String>,
        trustedServerId: String?,
    ): Boolean {
        val domain = senderDomainOf(senderAddress) ?: return false
        return !hasDmarcFail(authenticationResults, domain, trustedServerId)
    }

    /**
     * Names the first risky attachment and why, and counts the others.
     */
    @JvmStatic
    fun riskyAttachmentsText(context: Context, attachments: List<RiskyAttachment>): String? {
        val first = attachments.firstOrNull() ?: return null
        val reason = riskText(context, first.risk, first.name)
        val more = attachments.size - 1
        return if (more == 0) {
            context.getString(R.string.message_view_risky_attachment, reason)
        } else {
            context.resources.getQuantityString(R.plurals.message_view_risky_attachments, more, reason, more)
        }
    }

    /**
     * Asks before opening an attachment whose kind phishing and malware mail sends, and opens it only on a yes.
     * Other attachments are opened straight away.
     */
    @JvmStatic
    fun openAttachment(context: Context, fileName: String?, open: Runnable) {
        val risk = attachmentRisk(fileName)
        if (fileName == null || risk == null) {
            open.run()
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.attachment_risk_dialog_title, fileName))
            .setMessage(
                riskText(context, risk, fileName) + " " + context.getString(R.string.attachment_risk_dialog_advice),
            )
            .setPositiveButton(R.string.attachment_risk_dialog_open) { _, _ -> open.run() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun riskText(context: Context, risk: AttachmentRisk, name: String): String {
        val text = when (risk) {
            AttachmentRisk.WEB_PAGE -> R.string.attachment_risk_web_page
            AttachmentRisk.PROGRAM -> R.string.attachment_risk_program
            AttachmentRisk.DISK_IMAGE -> R.string.attachment_risk_disk_image
            AttachmentRisk.MACRO_DOCUMENT -> R.string.attachment_risk_macro_document
            AttachmentRisk.LOCKED_ARCHIVE -> R.string.attachment_risk_locked_archive
        }
        return context.getString(text, name)
    }
}
