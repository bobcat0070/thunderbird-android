package net.thunderbird.feature.attachments.internal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.attachments.OpenOutcome
import net.thunderbird.feature.attachments.SenderAttachmentRepository
import net.thunderbird.feature.attachments.StoredAttachment
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.DownloadProgress
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Effect
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Event
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Notice
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.State

/**
 * How many messages one tap on Download fetches. Each is downloaded whole, files and all, so a sender with years of
 * mail is fetched a screenful at a time rather than all at once.
 */
internal const val DOWNLOAD_BATCH_SIZE = 25

/**
 * Lists the files one sender sent, opens them, and downloads the sender's messages that are known only by their
 * envelope so their files can be listed too.
 */
internal class SenderAttachmentsViewModel(
    private val repository: SenderAttachmentRepository,
) : BaseViewModel<State, Event, Effect>(State()), SenderAttachmentsContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            is Event.LoadAttachments -> handleOneTimeEvent(event) { loadAttachments(event.address) }
            is Event.AttachmentClicked -> open(event.attachment)
            is Event.ShowMessageClicked -> repository.showMessage(event.attachment)
            Event.DownloadMessagesClicked -> downloadMessages()
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun loadAttachments(address: String) {
        updateState { it.copy(address = address) }
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val senderAttachments = repository.attachments(state.value.address)
        updateState {
            it.copy(
                isLoading = false,
                attachments = senderAttachments.attachments,
                messagesNotDownloaded = senderAttachments.messagesNotDownloaded,
            )
        }
    }

    private fun open(attachment: StoredAttachment) {
        if (state.value.openingKey != null) return
        updateState { it.copy(openingKey = attachment.key) }

        viewModelScope.launch {
            when (repository.open(attachment)) {
                OpenOutcome.OPENED -> Unit
                OpenOutcome.NO_VIEWER -> emitEffect(Effect.ShowNotice(Notice.NoViewer))
                OpenOutcome.FAILED -> emitEffect(Effect.ShowNotice(Notice.OpenFailed))
            }
            updateState { it.copy(openingKey = null) }

            // A file that had to be downloaded first is on the device now.
            if (!attachment.isDownloaded) reload()
        }
    }

    private fun downloadMessages() {
        if (state.value.download != null) return
        updateState { it.copy(download = DownloadProgress(done = 0, total = 0)) }

        viewModelScope.launch {
            val count = repository.downloadMessages(state.value.address, DOWNLOAD_BATCH_SIZE) { done, total ->
                updateState { it.copy(download = DownloadProgress(done, total)) }
            }
            emitEffect(Effect.ShowNotice(Notice.Downloaded(count)))
            reload()
            updateState { it.copy(download = null) }
        }
    }
}
