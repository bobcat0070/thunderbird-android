package net.thunderbird.feature.attachments.internal

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.attachments.StoredAttachment

internal interface SenderAttachmentsContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param address whose files are listed; `null` for everyone's.
     * @param attachments every file read for [period]; the list shows those of [type].
     * @param isLimited whether older files were left out because there were too many messages with files.
     * @param openingKey the file being opened, which may first have to be downloaded; other files wait meanwhile.
     * @param download how far downloading the not yet downloaded messages has got, while it runs.
     */
    data class State(
        val address: String? = null,
        val isLoading: Boolean = true,
        val attachments: List<StoredAttachment> = emptyList(),
        val type: AttachmentType = AttachmentType.ALL,
        val period: AttachmentPeriod = AttachmentPeriod.ANY_TIME,
        val messagesNotDownloaded: Int = 0,
        val isLimited: Boolean = false,
        val openingKey: String? = null,
        val download: DownloadProgress? = null,
    ) {
        val shownAttachments: List<StoredAttachment>
            get() = attachments.ofType(type)
    }

    data class DownloadProgress(val done: Int, val total: Int)

    sealed interface Event {
        /**
         * @param address whose files to list; `null` for everyone's.
         */
        data class LoadAttachments(val address: String?) : Event
        data class TypeClicked(val type: AttachmentType) : Event
        data class PeriodClicked(val period: AttachmentPeriod) : Event
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
