package net.thunderbird.feature.newsletter.internal

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.newsletter.NewsletterSender

internal interface NewsletterSendersContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param busyAddress the sender an action is running for, whose buttons are disabled meanwhile.
     * @param confirmation an action waiting for the reader to confirm it.
     */
    data class State(
        val isLoading: Boolean = true,
        val senders: List<NewsletterSender> = emptyList(),
        val busyAddress: String? = null,
        val confirmation: Confirmation? = null,
    )

    data class Confirmation(
        val sender: NewsletterSender,
        val action: Action,
    )

    enum class Action { UNSUBSCRIBE, ARCHIVE_ALL, DELETE_ALL }

    sealed interface Event {
        data object LoadSenders : Event
        data class SenderClicked(val sender: NewsletterSender) : Event
        data class ActionClicked(val sender: NewsletterSender, val action: Action) : Event
        data object ConfirmClicked : Event
        data object DismissClicked : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data class ShowNotice(val notice: Notice) : Effect
    }

    sealed interface Notice {
        data class Unsubscribed(val sender: NewsletterSender) : Notice
        data object UnsubscribeUnavailable : Notice
        data class Archived(val count: Int) : Notice
        data class Deleted(val count: Int) : Notice
    }
}
