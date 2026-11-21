package net.thunderbird.feature.plusaddress.internal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.plusaddress.internal.PlusAddressesContract.Effect
import net.thunderbird.feature.plusaddress.internal.PlusAddressesContract.Event
import net.thunderbird.feature.plusaddress.internal.PlusAddressesContract.State

internal interface PlusAddressesContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    data class State(
        val isLoading: Boolean = true,
        val records: List<PlusAddressRecord> = emptyList(),
    )

    sealed interface Event {
        data object Load : Event
        data class AllowSenderClicked(val key: String, val domain: String) : Event
        data class IgnoreClicked(val key: String, val isIgnored: Boolean) : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
    }
}

/**
 * Shows the reader's plus addresses, who each was given to, and who else wrote to it.
 */
internal class PlusAddressesViewModel(
    private val overview: PlusAddressOverview,
    private val decisions: PlusAddressDecisionStore,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BaseViewModel<State, Event, Effect>(State()), PlusAddressesContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.Load -> handleOneTimeEvent(event) { load(refresh = true) }

            is Event.AllowSenderClicked -> {
                decisions.allowSender(event.key, event.domain)
                load(refresh = false)
            }

            is Event.IgnoreClicked -> {
                decisions.setIgnored(event.key, event.isIgnored)
                load(refresh = false)
            }

            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun load(refresh: Boolean) {
        viewModelScope.launch {
            val records = withContext(backgroundDispatcher) { overview.records(refresh) }
            updateState { it.copy(isLoading = false, records = records) }
        }
    }
}
