package net.thunderbird.feature.mailboxrules.internal

import android.content.Intent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.mailboxrules.MailboxRulesSource
import net.thunderbird.feature.mailboxrules.RulesResult
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.Effect
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.Event
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.Phase
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.State

/**
 * Reads a Microsoft 365 mailbox's inbox rules when the reader asks, through a sign-in of its own each time, and lists
 * the ones that send mail away or hide it first.
 */
internal class MailboxRulesViewModel(
    private val source: MailboxRulesSource,
) : BaseViewModel<State, Event, Effect>(State()), MailboxRulesContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.LoadAccounts -> handleOneTimeEvent(event) { loadAccounts() }
            is Event.AccountClicked -> updateState { it.copy(account = event.account, phase = Phase.Ready) }
            Event.CheckClicked -> startSignIn()
            is Event.SignInFinished -> readRules(event.result)
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun loadAccounts() {
        val accounts = source.accounts()
        updateState { it.copy(accounts = accounts, account = accounts.firstOrNull()) }
    }

    private fun startSignIn() {
        val account = state.value.account
        if (account == null || state.value.phase == Phase.Working) return

        val intent = source.signInIntent(account.id)
        if (intent == null) {
            emitEffect(Effect.SignInUnavailable)
        } else {
            updateState { it.copy(phase = Phase.Working) }
            emitEffect(Effect.StartSignIn(intent))
        }
    }

    private fun readRules(result: Intent?) {
        val account = state.value.account ?: return

        viewModelScope.launch {
            val phase = when (val rulesResult = source.readRules(account.id, result)) {
                is RulesResult.Success -> Phase.Loaded(rulesResult.rules.sortedForReview(account.email))
                RulesResult.Canceled -> Phase.Ready
                is RulesResult.Refused -> Phase.Refused(rulesResult.reason)
                is RulesResult.Failed -> Phase.Failed(rulesResult.reason)
            }
            updateState { it.copy(phase = phase) }
        }
    }

    override fun onCleared() {
        source.close()
        super.onCleared()
    }
}
