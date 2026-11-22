package net.thunderbird.feature.attachments.internal

import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.attachments.AttachmentQuery
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
 * Lists the files one sender sent, or everyone's, opens them, and downloads the messages that are known only by their
 * envelope so their files can be listed too.
 *
 * The kind of file narrows what was read; how far back is read again, since only so many messages are read at once.
 */
internal class SenderAttachmentsViewModel(
    private val repository: SenderAttachmentRepository,
    private val clock: Clock,
) : BaseViewModel<State, Event, Effect>(State()), SenderAttachmentsContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            is Event.LoadAttachments -> handleOneTimeEvent(event) { loadAttachments(event.address) }
            is Event.TypeClicked -> updateState { it.copy(type = event.type) }
            is Event.PeriodClicked -> changePeriod(event.period)
            is Event.AttachmentClicked -> open(event.attachment)
            is Event.ShowMessageClicked -> repository.showMessage(event.attachment)
            Event.DownloadMessagesClicked -> downloadMessages()
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun loadAttachments(address: String?) {
        updateState { it.copy(address = address) }
        viewModelScope.launch { reload() }
    }

    private fun changePeriod(period: AttachmentPeriod) {
        if (period == state.value.period) return
        updateState { it.copy(period = period, isLoading = true) }
        viewModelScope.launch { reload() }
    }

    private fun query(): AttachmentQuery {
        val since = state.value.period.duration?.let { (clock.now() - it).toEpochMilliseconds() }
        return AttachmentQuery(senderAddress = state.value.address, since = since)
    }

    private suspend fun reload() {
        val period = state.value.period
        val result = repository.attachments(query())
        // A slower answer for a period since left behind is not shown.
        if (period != state.value.period) return

        updateState {
            it.copy(
                isLoading = false,
                attachments = result.attachments,
                messagesNotDownloaded = result.messagesNotDownloaded,
                isLimited = result.isLimited,
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
            val count = repository.downloadMessages(query(), DOWNLOAD_BATCH_SIZE) { done, total ->
                updateState { it.copy(download = DownloadProgress(done, total)) }
            }
            emitEffect(Effect.ShowNotice(Notice.Downloaded(count)))
            reload()
            updateState { it.copy(download = null) }
        }
    }
}
