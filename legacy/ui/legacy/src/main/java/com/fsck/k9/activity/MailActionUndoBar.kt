package com.fsck.k9.activity

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.controller.MailActionHold
import com.fsck.k9.controller.MailActionHold.HeldAction
import com.fsck.k9.controller.MailActionHold.Kind
import com.fsck.k9.ui.R
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.thunderbird.feature.account.AccountIdFactory

/**
 * Offers "Undo" at the bottom of the screen for as long as mail just deleted or moved is waiting in the
 * [MailActionHold].
 *
 * Its own class rather than more of the activity, which is already far past the size the project allows.
 *
 * @param host finds the view to show the bar in; the message list's coordinator when it is on screen, so the
 *   compose button moves up out of the way instead of sitting on top of "Undo".
 */
internal class MailActionUndoBar(
    private val activity: AppCompatActivity,
    private val mailActionHold: MailActionHold,
    private val messageStoreManager: MessageStoreManager,
    private val host: () -> View,
) {
    private var offeredAction: HeldAction? = null
    private var snackbar: Snackbar? = null

    fun start() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mailActionHold.heldActions.collect { heldActions -> offer(heldActions.lastOrNull()) }
            }
        }
    }

    /**
     * Carries out whatever is still waiting, for when the screen goes away and "Undo" with it. Left waiting, the
     * mail would only come back the next time the app starts, with nothing having happened to it.
     */
    fun releaseWaitingActions() {
        mailActionHold.releaseAll()
    }

    private fun offer(action: HeldAction?) {
        if (action == offeredAction) return
        offeredAction = action

        snackbar?.dismiss()
        val remainingMillis = action?.let { (it.releaseAtMillis - System.currentTimeMillis()).toInt() } ?: 0
        snackbar = action?.takeIf { remainingMillis > 0 }?.let { held ->
            Snackbar.make(host(), describe(held), remainingMillis)
                .setAction(R.string.undo_mail_action) { undo(held) }
                .apply { show() }
        }
    }

    private fun undo(action: HeldAction) {
        if (!mailActionHold.undo(action.id)) {
            Toast.makeText(activity, R.string.undo_mail_too_late, Toast.LENGTH_SHORT).show()
        }
    }

    private fun describe(action: HeldAction): String {
        val resources = activity.resources
        val count = action.messages.size

        return when (val kind = action.kind) {
            Kind.Delete -> resources.getQuantityString(R.plurals.undo_mail_deleted, count, count)
            Kind.Archive -> resources.getQuantityString(R.plurals.undo_mail_archived, count, count)
            Kind.Spam -> resources.getQuantityString(R.plurals.undo_mail_spam, count, count)
            is Kind.Move -> folderName(action, kind.folderId)
                ?.let { name -> resources.getQuantityString(R.plurals.undo_mail_moved_to, count, count, name) }
                ?: resources.getQuantityString(R.plurals.undo_mail_moved, count, count)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun folderName(action: HeldAction, folderId: Long): String? {
        val accountUuid = action.messages.firstOrNull()?.accountUuid ?: return null

        return try {
            messageStoreManager.getMessageStore(AccountIdFactory.of(accountUuid)).getFolder(folderId) { it.name }
        } catch (e: Exception) {
            // The bar still says what happened, without naming where.
            null
        }
    }
}
