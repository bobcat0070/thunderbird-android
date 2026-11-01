package net.thunderbird.feature.attachments.internal

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.attachments.StoredAttachment

internal interface SenderAttachmentsContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param openingKey the file being opened, which may first have to be downloaded; other files wait meanwhile.
     * @param download how far downloading the sender's not yet downloaded messages has got, while it runs.
     */
    data class State(
        val address: String = "",
        val isLoading: Boolean = true,
        val attachments: List<StoredAttachment> = emptyList(),
        val messagesNotDownloaded: Int = 0,
        val openingKey: String? = null,
        val download: DownloadProgress? = null,
    )

    data class DownloadProgress(val done: Int, val total: Int)

    sealed interface Event {
        data class LoadAttachments(val address: String) : Event
        data class AttachmentClicked(val attachment: StoredAttachment) : Event
        data class ShowMessageClicked(val attachment: StoredAttachment) : Event
        data object DownloadMessagesClicked : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data class ShowNotice(val notice: Notice) : Effect
    }

    sealed interface Notice {
        data object NoViewer : Notice
        data object OpenFailed : Notice
        data class Downloaded(val count: Int) : Notice
    }
}
