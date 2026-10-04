package net.thunderbird.feature.newsletter.internal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.newsletter.NewsletterActions
import net.thunderbird.feature.newsletter.NewsletterSender
import net.thunderbird.feature.newsletter.NewsletterSenderRepository
import net.thunderbird.feature.newsletter.UnsubscribeOutcome
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Action
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Confirmation
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Effect
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Event
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Notice
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.State

/**
 * Lists newsletter senders and acts on all of a sender's mail at once.
 *
 * Every action that sends something or moves mail is confirmed first, and the list is read again after each, so a
 * sender whose newsletters were archived or deleted drops out of it.
 */
internal class NewsletterSendersViewModel(
    private val repository: NewsletterSenderRepository,
    private val actions: NewsletterActions,
) : BaseViewModel<State, Event, Effect>(State()), NewsletterSendersContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.LoadSenders -> handleOneTimeEvent(event) { loadSenders() }
            is Event.SenderClicked -> actions.showAllMail(event.sender.address)
            is Event.ActionClicked -> updateState { it.copy(confirmation = Confirmation(event.sender, event.action)) }
            Event.ConfirmClicked -> confirm()
            Event.DismissClicked -> updateState { it.copy(confirmation = null) }
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun loadSenders() {
        viewModelScope.launch {
            val senders = repository.senders()
            updateState { it.copy(isLoading = false, senders = senders) }
        }
    }

    private fun confirm() {
        val confirmation = state.value.confirmation ?: return
        val sender = confirmation.sender
        updateState { it.copy(confirmation = null, busyAddress = sender.address) }

        viewModelScope.launch {
            val notice = when (confirmation.action) {
                Action.UNSUBSCRIBE -> unsubscribe(sender)
                Action.ARCHIVE_ALL -> Notice.Archived(actions.archiveAll(sender.address))
                Action.DELETE_ALL -> Notice.Deleted(actions.deleteAll(sender.address))
            }
            notice?.let { emitEffect(Effect.ShowNotice(it)) }

            val senders = repository.senders()
            updateState { it.copy(senders = senders, busyAddress = null) }
        }
    }

    private suspend fun unsubscribe(sender: NewsletterSender): Notice? {
        return when (actions.unsubscribe(sender.address)) {
            UnsubscribeOutcome.SENT -> Notice.Unsubscribed(sender)
            UnsubscribeOutcome.OPENED_ELSEWHERE -> null
            UnsubscribeOutcome.UNAVAILABLE -> Notice.UnsubscribeUnavailable
        }
    }
}
