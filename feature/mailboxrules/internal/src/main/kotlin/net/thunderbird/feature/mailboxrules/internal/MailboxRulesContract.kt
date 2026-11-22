package net.thunderbird.feature.mailboxrules.internal

import android.content.Intent
import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.mailboxrules.MailboxRule
import net.thunderbird.feature.mailboxrules.RulesAccount

internal interface MailboxRulesContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param account the mailbox whose rules are shown or about to be read.
     */
    data class State(
        val accounts: List<RulesAccount> = emptyList(),
        val account: RulesAccount? = null,
        val phase: Phase = Phase.Ready,
    )

    sealed interface Phase {
        /** Waiting for the reader to ask; nothing has been read. */
        data object Ready : Phase

        /** The sign-in is open, or the rules are being read. */
        data object Working : Phase

        data class Loaded(val rules: List<MailboxRule>) : Phase

        data class Refused(val reason: String?) : Phase

        data class Failed(val reason: String?) : Phase
    }

    sealed interface Event {
        data object LoadAccounts : Event
        data class AccountClicked(val account: RulesAccount) : Event
        data object CheckClicked : Event

        /**
         * @param result what the sign-in returned; `null` when it was closed.
         */
        data class SignInFinished(val result: Intent?) : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data class StartSignIn(val intent: Intent) : Effect
        data object SignInUnavailable : Effect
    }
}
