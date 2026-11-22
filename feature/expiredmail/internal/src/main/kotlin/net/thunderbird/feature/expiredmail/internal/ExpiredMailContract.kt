package net.thunderbird.feature.expiredmail.internal

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.expiredmail.ExpiredKind
import net.thunderbird.feature.expiredmail.ExpiredMail

internal interface ExpiredMailContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param isBusy whether an action is running; the buttons wait meanwhile.
     * @param confirmation an action on a whole group waiting for the reader to confirm it.
     */
    data class State(
        val isLoading: Boolean = true,
        val mails: List<ExpiredMail> = emptyList(),
        val isBusy: Boolean = false,
        val confirmation: Confirmation? = null,
    ) {
        /**
         * The mails by why they expired, in the order the screen shows the groups; empty groups are left out.
         */
        val groups: List<Pair<ExpiredKind, List<ExpiredMail>>>
            get() = ExpiredKind.entries.mapNotNull { kind ->
                mails.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
            }
    }

    data class Confirmation(val kind: ExpiredKind, val action: Action, val count: Int)

    enum class Action { ARCHIVE, DELETE }

    sealed interface Event {
        data object LoadMail : Event
        data class MailClicked(val mail: ExpiredMail) : Event
        data class MailActionClicked(val mail: ExpiredMail, val action: Action) : Event
        data class GroupActionClicked(val kind: ExpiredKind, val action: Action) : Event
        data object ConfirmClicked : Event
        data object DismissClicked : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data class ShowNotice(val action: Action, val count: Int) : Effect
    }
}
