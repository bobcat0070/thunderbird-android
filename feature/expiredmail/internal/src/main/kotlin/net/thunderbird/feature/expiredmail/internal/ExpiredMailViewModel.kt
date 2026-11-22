package net.thunderbird.feature.expiredmail.internal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.expiredmail.ExpiredMailSource
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Action
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Confirmation
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Effect
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Event
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.State

/**
 * Lists mail whose use is over and archives or deletes it, one mail at a time or a whole group.
 *
 * Nothing is done to mail without the reader asking: one mail at a time goes at a tap, a whole group only once the
 * reader confirms how many.
 */
internal class ExpiredMailViewModel(
    private val source: ExpiredMailSource,
) : BaseViewModel<State, Event, Effect>(State()), ExpiredMailContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.LoadMail -> handleOneTimeEvent(event) { viewModelScope.launch { reload() } }
            is Event.MailClicked -> source.openMail(event.mail.key)
            is Event.MailActionClicked -> act(event.action, listOf(event.mail.key))
            is Event.GroupActionClicked -> askToConfirm(event)
            Event.ConfirmClicked -> confirm()
            Event.DismissClicked -> updateState { it.copy(confirmation = null) }
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun askToConfirm(event: Event.GroupActionClicked) {
        val count = state.value.mails.count { it.kind == event.kind }
        if (count == 0) return
        updateState { it.copy(confirmation = Confirmation(event.kind, event.action, count)) }
    }

    private fun confirm() {
        val confirmation = state.value.confirmation ?: return
        updateState { it.copy(confirmation = null) }
        act(confirmation.action, state.value.mails.filter { it.kind == confirmation.kind }.map { it.key })
    }

    private fun act(action: Action, keys: List<String>) {
        if (state.value.isBusy || keys.isEmpty()) return
        updateState { it.copy(isBusy = true) }

        viewModelScope.launch {
            val count = when (action) {
                Action.ARCHIVE -> source.archive(keys)
                Action.DELETE -> source.delete(keys)
            }
            emitEffect(Effect.ShowNotice(action, count))
            // Moving mail runs in the background, so reading the list straight away could still find it.
            val done = keys.toSet()
            updateState { state -> state.copy(isBusy = false, mails = state.mails.filterNot { it.key in done }) }
        }
    }

    private suspend fun reload() {
        val mails = source.expiredMail()
        updateState { it.copy(isLoading = false, mails = mails) }
    }
}
